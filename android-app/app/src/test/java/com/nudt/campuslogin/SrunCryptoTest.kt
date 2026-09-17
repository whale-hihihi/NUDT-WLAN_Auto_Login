package com.nudt.campuslogin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 跨语言一致性验证: 向量由 PC 端已实测通过的 Python 实现(srun.py)生成,
 * 覆盖 XXTEA + 自定义base64 + HMAC-MD5 + SHA-1 的完整输出。
 * 向量文件为 TSV: username, password, ip, acId, token, info, hmd5, chksum
 */
class SrunCryptoTest {

    private data class Vector(
        val username: String, val password: String, val ip: String,
        val acId: String, val token: String, val info: String,
        val hmd5: String, val chksum: String,
    )

    private fun vectors(): List<Vector> {
        val text = javaClass.classLoader!!.getResourceAsStream("srun_vectors.tsv")!!
            .readBytes().toString(Charsets.UTF_8)
        return text.trim().lines().map { line ->
            val f = line.split("\t")
            Vector(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7])
        }
    }

    @Test
    fun cryptoMatchesPythonReferenceVectors() {
        val vs = vectors()
        assertEquals("参考向量组数", 3, vs.size)
        vs.forEach { v ->
            val tag = "[${v.username}]"
            assertEquals("$tag hmac-md5", v.hmd5, SrunCrypto.hmacMd5Hex(v.password, v.token))
            val info = SrunCrypto.encodeInfo(v.username, v.password, v.ip, v.acId, v.token)
            assertEquals("$tag info (XXTEA+customBase64+JSON)", v.info, info)
            val expectedChk = SrunCrypto.sha1Hex(
                v.token + v.username + v.token + v.hmd5 + v.token + v.acId +
                    v.token + v.ip + v.token + "200" + v.token + "1" + v.token + info
            )
            assertEquals("$tag chksum 拼接", v.chksum, expectedChk)
        }
    }

    @Test
    fun customBase64KnownVector() {
        // 由 Python 实实现算: _custom_b64(b'abc')=='ZaRk', b'ab'=='Za2=', b'a'=='Z+=='
        // 独立小样本(含 1/2/3 字节三种补位情形), 排除向量文件本身的传递错误
        assertEquals("ZaRk", SrunCrypto.customBase64("abc".toByteArray(Charsets.US_ASCII)))
        assertEquals("Za2=", SrunCrypto.customBase64("ab".toByteArray(Charsets.US_ASCII)))
        assertEquals("Z+==", SrunCrypto.customBase64("a".toByteArray(Charsets.US_ASCII)))
    }
}
