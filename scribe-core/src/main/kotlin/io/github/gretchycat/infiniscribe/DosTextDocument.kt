package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.CancellationToken
import java.io.IOException

/** A text terminal, not a bitmap: fixed-width cells, runs of attributes, and extracted music commands. */
data class DosTextRun(val start: Int, val end: Int, val foreground: Int, val background: Int)
data class DosTextDocument(val text: String, val rows: Int, val columns: Int, val runs: List<DosTextRun>, val music: List<String>)

object DosTextDocuments {
    private val palette = intArrayOf(0xff000000.toInt(),0xffaa0000.toInt(),0xff00aa00.toInt(),0xffaa5500.toInt(),
        0xff0000aa.toInt(),0xffaa00aa.toInt(),0xff00aaaa.toInt(),0xffaaaaaa.toInt(),
        0xff555555.toInt(),0xffff5555.toInt(),0xff55ff55.toInt(),0xffffff55.toInt(),
        0xff5555ff.toInt(),0xffff55ff.toInt(),0xff55ffff.toInt(),0xffffffff.toInt())
    private val graphics = "\u0000☺☻♥♦♣♠•◘○◙♂♀♪♫☼►◄↕‼¶§▬↨↑↓→←∟↔▲▼"
    private val cp437Chars = CharArray(256) { code ->
        when { code<32 -> graphics[code]; code==127 -> '⌂'; else -> String(byteArrayOf(code.toByte()),DosTextEncoding.cp437)[0] }
    }
    private val musicChars = Regex("[a-gA-GlLmMnNoOpPsStT0-9.\\-+#<> ]*")
    fun parse(bytes: ByteArray, columns: Int = 80, maxRows: Int = 65536,
        cancellation: CancellationToken = CancellationToken.NONE): DosTextDocument {
        require(columns in 1..320 && maxRows in 1..65536)
        if (bytes.size > 4 * 1024 * 1024) throw IOException("DOS text exceeds 4 MiB limit")
        class Row {
            val chars=Array(columns) { " " }; val fg=IntArray(columns) { palette[7] }; val bg=IntArray(columns) { palette[0] }
        }
        val rows=ArrayList<Row>(); rows.add(Row())
        var x=0; var y=0; var savedX=0; var savedY=0
        var fg=7; var bg=0; var bold=false; var inverse=false; var hidden=false
        var customFg:Int?=null; var customBg:Int?=null
        var wrapPending=false
        fun ensure(row:Int) {
            if (row !in 0 until maxRows) throw IOException("DOS text exceeds $maxRows rows")
            while(rows.size<=row) rows.add(Row())
        }
        fun foreground() = if(hidden) customBg ?: palette[bg] else customFg ?: palette[if(bold && fg<8) fg+8 else fg]
        fun clear(row:Int,from:Int,to:Int) {
            ensure(row)
            for(c in from.coerceIn(0,columns)..to.coerceIn(-1,columns-1)) {
                rows[row].chars[c]=" "; rows[row].fg[c]=foreground();rows[row].bg[c]=customBg ?: palette[bg]
            }
        }
        fun put(c:Int) {
            val category=Character.getType(c)
            if(category==Character.NON_SPACING_MARK.toInt() || category==Character.COMBINING_SPACING_MARK.toInt() ||
                category==Character.ENCLOSING_MARK.toInt()) {
                val previous=if(wrapPending)x else x-1
                if(previous>=0)rows[y].chars[previous]+=String(Character.toChars(c))
                return
            }
            if(wrapPending) { x=0;y++;wrapPending=false }
            ensure(y)
            rows[y].chars[x]=String(Character.toChars(c))
            val f=foreground();val b=customBg ?: palette[bg]
            rows[y].fg[x]=if(inverse)b else f;rows[y].bg[x]=if(inverse)f else b
            if(x==columns-1)wrapPending=true else x++
        }
        fun color(index:Int):Int = when {
            index<16 -> palette[index.coerceAtLeast(0)]
            index<232 -> { val n=index-16;val levels=intArrayOf(0,95,135,175,215,255);(0xff000000L or (levels[n/36].toLong() shl 16) or (levels[(n/6)%6].toLong() shl 8) or levels[n%6].toLong()).toInt() }
            else -> {val n=(8+(index.coerceAtMost(255)-232)*10).coerceIn(0,255);(0xff000000L or (n.toLong() shl 16) or (n.toLong() shl 8) or n.toLong()).toInt()}
        }
        val music=ArrayList<String>()
        var end=bytes.size
        if(end>=128 && String(bytes,end-128,7,Charsets.US_ASCII)=="SAUCE00") end-=128
        val detection=DosTextEncoding.detect(bytes)
        val utf8=detection.charset==Charsets.UTF_8 && detection.evidence!="Encoding uncertain" &&
            bytes.any { it.toInt() and 128 != 0 }
        val content=if(utf8)String(bytes,0,end,Charsets.UTF_8).removePrefix("\uFEFF").codePoints().toArray()
            else IntArray(end) { cp437Chars[bytes[it].toInt() and 255].code }
        // Keep ASCII controls as controls; CP437's graphical C0 symbols apply only to printable cells.
        if(!utf8)for(i in 0 until end)if((bytes[i].toInt() and 255)<32)content[i]=bytes[i].toInt() and 255
        end=content.size
        var at=0
        while(at<end) {
            if(at and 1023==0)cancellation.check()
            val b=content[at++]
            if(b==26)break // DOS EOF; never display a SAUCE/comment trailer.
            if(b==27) {
                if(at>=end)break
                val next=content[at++]
                if(next=='7'.code) {savedX=x;savedY=y;continue}
                if(next=='8'.code) {x=savedX;y=savedY;wrapPending=false;continue}
                if(next!='['.code)continue
                val start=at
                while(at<end && at-start<128 && content[at] !in 0x40..0x7e)at++
                if(at>=end || at-start>=128)continue
                val command=content[at++].toChar()
                val parameters=String(content,start,at-start-1)
                if(command in charArrayOf('M','N','|') && parameters.isEmpty()) {
                    var finish=at
                    while(finish<end && finish-at<16384 && content[finish]!=14 && content[finish]!=27)finish++
                    if(finish<end && content[finish]==14) {
                        var score=String(content,at,finish-at)
                        if(score.isNotEmpty() && musicChars.matches(score)) {
                            if(command=='M' && (score.first().uppercaseChar() in "BFLS" ||
                                (score.first().uppercaseChar()=='N' && score.getOrNull(1)?.isDigit()!=true)))score="M$score"
                            if(music.size>=4096)throw IOException("Too many ANSI music strings")
                            music.add(score);at=finish+1;continue
                        }
                    }
                }
                val args=parameters.removePrefix("?").split(';').map { it.toIntOrNull()?.coerceIn(0,1000000) ?: 0 }
                fun arg(n:Int=0,default:Int=1)=args.getOrNull(n)?.takeIf {it!=0} ?: default
                when(command) {
                    'A' -> y=(y-arg()).coerceAtLeast(0)
                    'B' -> {y=(y+arg()).coerceAtMost(maxRows-1);ensure(y)}
                    'C' -> x=(x+arg()).coerceAtMost(columns-1)
                    'D' -> x=(x-arg()).coerceAtLeast(0)
                    'E' -> {y=(y+arg()).coerceAtMost(maxRows-1);x=0;ensure(y)}
                    'F' -> {y=(y-arg()).coerceAtLeast(0);x=0}
                    'G' -> x=(arg()-1).coerceIn(0,columns-1)
                    'H','f' -> {y=(arg()-1).coerceIn(0,maxRows-1);x=(arg(1)-1).coerceIn(0,columns-1);ensure(y)}
                    's' -> {savedX=x;savedY=y}
                    'u' -> {x=savedX;y=savedY}
                    'J' -> when(args.first()) {
                        0 -> {clear(y,x,columns-1);for(r in y+1 until rows.size)clear(r,0,columns-1)}
                        1 -> {for(r in 0 until y)clear(r,0,columns-1);clear(y,0,x)}
                        2,3 -> {for(r in rows.indices)clear(r,0,columns-1)}
                    }
                    'K' -> when(args.first()) {0->clear(y,x,columns-1);1->clear(y,0,x);2->clear(y,0,columns-1)}
                    'm' -> {
                        var i=0
                        while(i<args.size) {
                            when(val a=args[i++]) {
                                0 -> {fg=7;bg=0;bold=false;inverse=false;hidden=false;customFg=null;customBg=null}
                                1 -> bold=true
                                22 -> bold=false
                                7 -> inverse=true
                                27 -> inverse=false
                                8 -> hidden=true
                                28 -> hidden=false
                                in 30..37 -> {fg=a-30;customFg=null}
                                in 40..47 -> {bg=a-40;customBg=null}
                                in 90..97 -> {fg=a-90+8;customFg=null}
                                in 100..107 -> {bg=a-100+8;customBg=null}
                                39 -> {fg=7;customFg=null}
                                49 -> {bg=0;customBg=null}
                                38,48 -> {
                                    val mode=args.getOrNull(i++)
                                    val c=when(mode) {
                                        5 -> args.getOrNull(i++)?.let(::color)
                                        2 -> if(i+2<args.size) {val r=args[i++].coerceIn(0,255);val g=args[i++].coerceIn(0,255);val blue=args[i++].coerceIn(0,255);(0xff000000L or(r.toLong() shl 16)or(g.toLong() shl 8)or blue.toLong()).toInt()} else null
                                        else -> null
                                    }
                                    if(c!=null) {if(a==38)customFg=c else customBg=c}
                                }
                            }
                        }
                    }
                }
                if(command!='m')wrapPending=false
                continue
            }
            when(b) {
                0,7,14,15 -> {}
                8 -> {x=(x-1).coerceAtLeast(0);wrapPending=false}
                9 -> {if(wrapPending){x=0;y++;ensure(y);wrapPending=false};val stop=((x/8+1)*8).coerceAtMost(columns);repeat((stop-x).coerceAtLeast(1)) {put(32)}}
                10 -> {y++;ensure(y);x=0;wrapPending=false}
                13 -> {x=0;wrapPending=false}
                else -> put(if(!utf8 && b<32)cp437Chars[b].code else b)
            }
        }
        cancellation.check()
        val text=StringBuilder(rows.size*(columns+1));val runs=ArrayList<DosTextRun>()
        for((r,row) in rows.withIndex()) {
            if(r and 255==0)cancellation.check()
            val offsets=IntArray(columns+1)
            for(c in 0 until columns) {offsets[c]=text.length;text.append(row.chars[c])}
            offsets[columns]=text.length
            var start=0
            while(start<columns) {
                var stop=start+1
                while(stop<columns && row.fg[stop]==row.fg[start] && row.bg[stop]==row.bg[start])stop++
                if(row.fg[start]!=palette[7] || row.bg[start]!=palette[0]) {
                    if(runs.size>=262144)throw IOException("ANSI styling exceeds display limit")
                    runs.add(DosTextRun(offsets[start],offsets[stop],row.fg[start],row.bg[start]))
                }
                start=stop
            }
            if(r<rows.lastIndex)text.append('\n')
        }
        return DosTextDocument(text.toString(),rows.size,columns,runs,music)
    }
}
