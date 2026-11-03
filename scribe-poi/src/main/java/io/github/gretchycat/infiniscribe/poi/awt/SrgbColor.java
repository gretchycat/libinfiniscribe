package io.github.gretchycat.infiniscribe.poi.awt;

/** sRGB value adapter for POI's color APIs on Android. No desktop painting API. */
public final class SrgbColor implements java.io.Serializable {
    private final int value;
    public static final SrgbColor white=new SrgbColor(255,255,255), WHITE=white;
    public static final SrgbColor lightGray=new SrgbColor(192,192,192), LIGHT_GRAY=lightGray;
    public static final SrgbColor gray=new SrgbColor(128,128,128), GRAY=gray;
    public static final SrgbColor darkGray=new SrgbColor(64,64,64), DARK_GRAY=darkGray;
    public static final SrgbColor black=new SrgbColor(0,0,0), BLACK=black;
    public static final SrgbColor red=new SrgbColor(255,0,0), RED=red;
    public static final SrgbColor pink=new SrgbColor(255,175,175), PINK=pink;
    public static final SrgbColor orange=new SrgbColor(255,200,0), ORANGE=orange;
    public static final SrgbColor yellow=new SrgbColor(255,255,0), YELLOW=yellow;
    public static final SrgbColor green=new SrgbColor(0,255,0), GREEN=green;
    public static final SrgbColor magenta=new SrgbColor(255,0,255), MAGENTA=magenta;
    public static final SrgbColor cyan=new SrgbColor(0,255,255), CYAN=cyan;
    public static final SrgbColor blue=new SrgbColor(0,0,255), BLUE=blue;
    public SrgbColor(int rgb) { this(rgb,false); }
    public SrgbColor(int rgba,boolean alpha) { value=alpha?rgba:rgba|0xff000000; }
    public SrgbColor(int r,int g,int b) { this(r,g,b,255); }
    public SrgbColor(int r,int g,int b,int a) {
        if((r|g|b|a)<0 || r>255 || g>255 || b>255 || a>255)throw new IllegalArgumentException("Color component out of range");
        value=(a<<24)|(r<<16)|(g<<8)|b;
    }
    public SrgbColor(float r,float g,float b) {this(r,g,b,1f);}
    public SrgbColor(float r,float g,float b,float a) {this(component(r),component(g),component(b),component(a));}
    private static int component(float v) {
        if(!Float.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("Color component out of range");
        return Math.round(v*255);
    }
    public int getRed(){return(value>>>16)&255;}
    public int getGreen(){return(value>>>8)&255;}
    public int getBlue(){return value&255;}
    public int getAlpha(){return value>>>24;}
    public int getRGB(){return value;}
    public float[] getRGBComponents(float[] out){if(out==null)out=new float[4];getRGBColorComponents(out);out[3]=getAlpha()/255f;return out;}
    public float[] getRGBColorComponents(float[] out){if(out==null)out=new float[3];out[0]=getRed()/255f;out[1]=getGreen()/255f;out[2]=getBlue()/255f;return out;}
    public float[] getComponents(float[] out){return getRGBComponents(out);}
    public float[] getColorComponents(float[] out){return getRGBColorComponents(out);}
    public SrgbColor darker(){return new SrgbColor((int)(getRed()*.7),(int)(getGreen()*.7),(int)(getBlue()*.7),getAlpha());}
    public SrgbColor brighter(){
        int r=getRed(),g=getGreen(),b=getBlue();
        if((r|g|b)==0)return new SrgbColor(3,3,3,getAlpha());
        return new SrgbColor(bright(r),bright(g),bright(b),getAlpha());
    }
    private static int bright(int v){return v==0?0:Math.min(255,(int)(Math.max(3,v)/.7));}
    public static SrgbColor decode(String value){return new SrgbColor(Integer.decode(value));}
    public static SrgbColor getColor(String name){return getColor(name,(SrgbColor)null);}
    public static SrgbColor getColor(String name,int fallback){return getColor(name,new SrgbColor(fallback));}
    public static SrgbColor getColor(String name,SrgbColor fallback){String v=System.getProperty(name);try{return v==null?fallback:decode(v);}catch(NumberFormatException e){return fallback;}}
    public static int HSBtoRGB(float h,float s,float b){
        int sector=(int)Math.floor((h-(float)Math.floor(h))*6);float f=(h-(float)Math.floor(h))*6-sector;
        float p=b*(1-s),q=b*(1-s*f),t=b*(1-s*(1-f));
        float r,g,bl;switch(sector){case 0:r=b;g=t;bl=p;break;case 1:r=q;g=b;bl=p;break;case 2:r=p;g=b;bl=t;break;case 3:r=p;g=q;bl=b;break;case 4:r=t;g=p;bl=b;break;default:r=b;g=p;bl=q;}
        return 0xff000000|(Math.round(r*255)<<16)|(Math.round(g*255)<<8)|Math.round(bl*255);
    }
    public static SrgbColor getHSBColor(float h,float s,float b){return new SrgbColor(HSBtoRGB(h,s,b));}
    public static float[] RGBtoHSB(int r,int g,int b,float[] out){
        if(out==null)out=new float[3];int max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
        float d=max-min,h=0;if(d!=0){if(max==r)h=(g-b)/d;else if(max==g)h=2+(b-r)/d;else h=4+(r-g)/d;h/=6;if(h<0)h+=1;}
        out[0]=h;out[1]=max==0?0:d/max;out[2]=max/255f;return out;
    }
    @Override public boolean equals(Object other){return other instanceof SrgbColor&&((SrgbColor)other).value==value;}
    @Override public int hashCode(){return value;}
    @Override public String toString(){return "sRGB["+getRed()+","+getGreen()+","+getBlue()+","+getAlpha()+"]";}
}
