#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "mobi.h"

static void fail(JNIEnv *env,const char *message) {
    if(!(*env)->ExceptionCheck(env))(*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),message);
}
static jbyteArray bytes(JNIEnv *env,const unsigned char *data,size_t size) {
    if(size>16*1024*1024){fail(env,"Book resource exceeds 16 MiB limit");return NULL;}
    jbyteArray result=(*env)->NewByteArray(env,(jsize)size);
    if(result && size)(*env)->SetByteArrayRegion(env,result,0,(jsize)size,(const jbyte *)data);
    return result;
}
static int emit(JNIEnv *env,jobject sink,jmethodID write,const char *name,const unsigned char *data,size_t size) {
    jstring path=(*env)->NewStringUTF(env,name);
    jbyteArray body=bytes(env,data,size);
    if(path && body && !(*env)->ExceptionCheck(env))(*env)->CallVoidMethod(env,sink,write,path,body);
    if(path)(*env)->DeleteLocalRef(env,path);if(body)(*env)->DeleteLocalRef(env,body);
    return !(*env)->ExceptionCheck(env);
}
static MOBIData *load(JNIEnv *env,jbyteArray encoded) {
    jsize size=(*env)->GetArrayLength(env,encoded);
    if(size<=0 || size>8192){fail(env,"Invalid book source path");return NULL;}
    char *path=calloc((size_t)size+1,1);
    if(!path){fail(env,"Book path allocation failed");return NULL;}
    (*env)->GetByteArrayRegion(env,encoded,0,size,(jbyte *)path);
    FILE *file=fopen(path,"rb");free(path);
    if(!file){fail(env,"Cannot open MOBI source");return NULL;}
    MOBIData *book=mobi_init();
    if(!book){fclose(file);fail(env,"Cannot allocate MOBI reader");return NULL;}
    MOBI_RET ret=mobi_load_file(book,file);fclose(file);
    if(ret!=MOBI_SUCCESS){mobi_free(book);fail(env,"Invalid or unsupported MOBI document");return NULL;}
    if(mobi_is_encrypted(book)){mobi_free(book);fail(env,"Encrypted MOBI books are not supported");return NULL;}
    if(mobi_get_text_maxsize(book)>16*1024*1024){mobi_free(book);fail(env,"Book text exceeds 16 MiB reader limit");return NULL;}
    return book;
}
static MOBIPdbRecord *cover_record(MOBIData *book) {
    MOBIExthHeader *cover=mobi_get_exthrecord_by_tag(book,EXTH_COVEROFFSET);
    if(!cover)return NULL;
    size_t first=mobi_get_first_resource_record(book);
    uint32_t offset=mobi_decode_exthvalue(cover->data,cover->size);
    if(first==MOBI_NOTSET || (size_t)offset>SIZE_MAX-first)return NULL;
    return mobi_get_record_by_seqnumber(book,first+offset);
}
JNIEXPORT jobjectArray JNICALL Java_io_github_gretchycat_infiniscribe_mobi_MobiNative_extract
(JNIEnv *env,jobject self,jbyteArray encoded,jobject sink) {
    (void)self;
    MOBIData *book=load(env,encoded);if(!book)return NULL;
    MOBIRawml *raw=mobi_init_rawml(book);jobjectArray result=NULL;char **names=NULL;size_t count=0;
    if(!raw){fail(env,"Cannot allocate MOBI content");goto done;}
    if(mobi_parse_rawml_opt(raw,book,true,false,true)!=MOBI_SUCCESS){fail(env,"MOBI content cannot be reconstructed");goto done;}
    jmethodID write=(*env)->GetMethodID(env,(*env)->GetObjectClass(env,sink),"writePart","(Ljava/lang/String;[B)V");
    if(!write)goto done;
    for(MOBIPart *part=raw->markup;part;part=part->next)if(++count>2000){fail(env,"Too many MOBI chapters");goto done;}
    if(!count){fail(env,"No readable MOBI chapters");goto done;}
    names=calloc(count,sizeof(char *));if(!names){fail(env,"Cannot allocate MOBI chapters");goto done;}
    size_t index=0,total=0,parts=0;MOBIPart *groups[3]={raw->markup,raw->flow?raw->flow->next:NULL,raw->resources};
    for(int group=0;group<3;group++)for(MOBIPart *part=groups[group];part;part=part->next) {
        if(++parts>10000 || part->size>16*1024*1024 || total>64*1024*1024-part->size){fail(env,"MOBI resources exceed reader limit");goto done;}
        total+=part->size;
        MOBIFileMeta meta=mobi_get_filemeta_by_type(part->type);char name[96];
        snprintf(name,sizeof(name),"%s%05zu.%s",group==0?"part":group==1?"flow":"resource",part->uid,meta.extension);
        if(!emit(env,sink,write,name,part->data,part->size))goto done;
        if(group==0){names[index]=strdup(name);if(!names[index++]){fail(env,"Cannot allocate chapter path");goto done;}}
    }
    char cover_name[32]="";MOBIPdbRecord *cover=cover_record(book);
    if(cover && cover->size>=4) {
        const char *ext=NULL;
        if(!memcmp(cover->data,"\xff\xd8\xff",3))ext="jpg";
        else if(!memcmp(cover->data,"\x89PNG",4))ext="png";
        else if(!memcmp(cover->data,"GIF8",4))ext="gif";
        if(ext){snprintf(cover_name,sizeof(cover_name),"cover.%s",ext);if(!emit(env,sink,write,cover_name,cover->data,cover->size))goto done;}
    }
    result=(*env)->NewObjectArray(env,(jsize)(count+3),(*env)->FindClass(env,"[B"),NULL);
    if(!result)goto done;
    char *title=mobi_meta_get_title(book),*author=mobi_meta_get_author(book);
    for(size_t i=0;i<count+3;i++) {
        const char *value=i==0?(title?title:""):i==1?(author?author:""):i==2?cover_name:names[i-3];
        jbyteArray text=bytes(env,(const unsigned char *)value,strlen(value));
        if(!text)break;
        (*env)->SetObjectArrayElement(env,result,(jsize)i,text);(*env)->DeleteLocalRef(env,text);
        if((*env)->ExceptionCheck(env))break;
    }
    free(title);free(author);
 done:
    if(names){for(size_t i=0;i<count;i++)free(names[i]);free(names);}
    if(raw)mobi_free_rawml(raw);mobi_free(book);return result;
}
JNIEXPORT jbyteArray JNICALL Java_io_github_gretchycat_infiniscribe_mobi_MobiNative_cover
(JNIEnv *env,jobject self,jbyteArray encoded) {
    (void)self;MOBIData *book=load(env,encoded);if(!book)return NULL;
    MOBIPdbRecord *part=cover_record(book);jbyteArray result=part?bytes(env,part->data,part->size):NULL;
    mobi_free(book);return result;
}
