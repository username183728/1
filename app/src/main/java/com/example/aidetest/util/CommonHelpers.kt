package com.example.aidetest

import java.io.ByteArrayOutputStream
import java.util.Locale

private val tokenPattern = Regex("(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})")

/** Catat error yang sengaja diabaikan, dengan pola token GitHub disamarkan. */
internal fun logSwallowed(where: String, t: Throwable) {
    val msg = t.message.orEmpty().replace(tokenPattern, "***").take(160)
    android.util.Log.w("GITLS", "$where: ${t.javaClass.simpleName} $msg")
}

internal fun <T> Result<T>.logFailure(where: String): Result<T> = onFailure { logSwallowed(where, it) }

internal class SimpleTextWatcher(val fn:(String)->Unit): android.text.TextWatcher {
    override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){}
    override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){fn(s?.toString()?:"")}
    override fun afterTextChanged(s:android.text.Editable?){}
}

internal object Base32 {
    private const val ALPH="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    fun encode(data:ByteArray):String {
        var buffer=0; var bits=0; val out=StringBuilder()
        for(b in data) {
            buffer=(buffer shl 8) or (b.toInt() and 255); bits+=8
            while(bits>=5){ bits-=5; out.append(ALPH[(buffer shr bits) and 31]) }
        }
        if(bits>0) out.append(ALPH[(buffer shl (5-bits)) and 31])
        return out.toString()
    }
    fun decode(s:String):ByteArray {
        var buffer=0; var bits=0; val out=ByteArrayOutputStream()
        for(ch in s.uppercase(Locale.getDefault()).replace("=","").filter { !it.isWhitespace() }) {
            val v=ALPH.indexOf(ch); require(v>=0)
            buffer=(buffer shl 5) or v; bits+=5
            if(bits>=8){bits-=8; out.write((buffer shr bits) and 255)}
        }
        return out.toByteArray()
    }
}
