package com.nudt.campuslogin

/**
 * 深澜(SRun)门户加密算法 —— 从门户自身 JS 逐行移植(与 PC 版 srun.py 等价):
 *   - XXTEA(btea)            : Portal.js _encodeUserInfo 内的 encode()
 *   - 自定义字母表 base64    : jquery-base64 v1.0, 字节直编, '=' 补位
 *   - md5(password, token)   : js-md5 v2.10.0 双参数 = HMAC-MD5
 *   - sha1                   : js-sha1 v0.6.0 标准 SHA-1
 *
 * 纯 Kotlin 无 Android 依赖, 便于 JVM 单元测试对照 Python 参考向量。
 */
object SrunCrypto {

    private const val B64_ALPHA = "LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA"
    private val DELTA = 0x9E3779B9.toInt()

    /** jquery-base64 _encode(): 对字节直接查自定义字母表, '=' 补位, 不做 UTF-8 转换 */
    fun customBase64(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < data.size) {
            val b10 = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(B64_ALPHA[b10 ushr 18 and 63])
            sb.append(B64_ALPHA[b10 ushr 12 and 63])
            sb.append(B64_ALPHA[b10 ushr 6 and 63])
            sb.append(B64_ALPHA[b10 and 63])
            i += 3
        }
        val rem = data.size - i
        if (rem == 1) {
            val b10 = (data[i].toInt() and 0xFF) shl 16
            sb.append(B64_ALPHA[b10 ushr 18 and 63])
            sb.append(B64_ALPHA[b10 ushr 12 and 63])
            sb.append("==")
        } else if (rem == 2) {
            val b10 = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8)
            sb.append(B64_ALPHA[b10 ushr 18 and 63])
            sb.append(B64_ALPHA[b10 ushr 12 and 63])
            sb.append(B64_ALPHA[b10 ushr 6 and 63])
            sb.append("=")
        }
        return sb.toString()
    }

    /** js-md5 双参数形式 md5(message, key) = HMAC-MD5 */
    fun hmacMd5Hex(message: String, key: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacMD5")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacMD5"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun sha1Hex(text: String): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8)).toHex()

    /** Portal.js s(): 字节小端打包为 Int 数组, 缺位补零; appendLength 时附加原始长度 */
    private fun toWords(data: ByteArray, appendLength: Boolean): IntArray {
        val words = IntArray((data.size + 3) / 4 + if (appendLength) 1 else 0)
        for (i in data.indices) {
            words[i / 4] = words[i / 4] or ((data[i].toInt() and 0xFF) shl (8 * (i % 4)))
        }
        if (appendLength) words[words.size - 1] = data.size
        return words
    }

    private fun fromWords(words: IntArray): ByteArray {
        val out = ByteArray(words.size * 4)
        for (i in words.indices) {
            val w = words[i]
            out[i * 4] = (w and 0xFF).toByte()
            out[i * 4 + 1] = (w ushr 8 and 0xFF).toByte()
            out[i * 4 + 2] = (w ushr 16 and 0xFF).toByte()
            out[i * 4 + 3] = (w ushr 24 and 0xFF).toByte()
        }
        return out
    }

    /** Portal.js encode(): XXTEA btea 加密(Int 溢出回绕与 JS int32 行为一致) */
    fun xxteaEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val v = toWords(data, appendLength = true)
        val k0 = toWords(key, appendLength = false)
        val k = IntArray(maxOf(4, k0.size))
        k0.copyInto(k)
        val n = v.size - 1
        var z = v[n]
        var y = v[0]
        val q = 6 + 52 / (n + 1)
        var d = 0
        var p: Int
        repeat(q) {
            d += DELTA
            val e = (d ushr 2) and 3
            p = 0
            while (p < n) {
                y = v[p + 1]
                var m = (z ushr 5) xor (y shl 2)
                m += (y ushr 3) xor (z shl 4) xor (d xor y)
                m += k[(p and 3) xor e] xor z
                v[p] = v[p] + m
                z = v[p]
                p++
            }
            // 注意: 与 JS 一致, 循环结束后 p == n, 最后一轮密钥下标用 n
            y = v[0]
            var m = (z ushr 5) xor (y shl 2)
            m += (y ushr 3) xor (z shl 4) xor (d xor y)
            m += k[(p and 3) xor e] xor z
            v[n] = v[n] + m
            z = v[n]
        }
        return fromWords(v)
    }

    /** 构造 {SRBX1}xxx 的 info 参数, JSON 键序与 JS 对象字面量一致, 非ASCII转义 */
    fun encodeInfo(username: String, password: String, ip: String, acId: String, token: String): String {
        val json = "{\"username\":${username.jsonQuote()},\"password\":${password.jsonQuote()}," +
            "\"ip\":${ip.jsonQuote()},\"acid\":${acId.jsonQuote()},\"enc_ver\":\"srun_bx1\"}"
        val enc = xxteaEncrypt(
            json.toByteArray(Charsets.US_ASCII),
            token.toByteArray(Charsets.US_ASCII)
        )
        return "{SRBX1}" + customBase64(enc)
    }

    /** 与 JSON.stringify/json.dumps(ensure_ascii) 一致的字符串转义 */
    private fun String.jsonQuote(): String {
        val sb = StringBuilder("\"")
        for (ch in this) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 || ch.code > 0x7E ->
                    sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 15])
        }
        return sb.toString()
    }
}
