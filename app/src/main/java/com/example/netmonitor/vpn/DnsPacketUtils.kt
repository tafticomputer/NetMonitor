package com.example.netmonitor.vpn

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** یک پرسش DNS استخراج‌شده از پکت ورودی TUN */
data class ParsedDnsQuery(
    val srcIp: InetAddress,
    val srcPort: Int,
    val dstIp: InetAddress,
    val dstPort: Int,
    val domain: String,
    val rawDnsPayload: ByteArray
)

object DnsPacketUtils {

    /**
     * سعی می‌کند یک پکت IPv4 خام را به‌عنوان UDP+DNS پارس کند.
     * اگر پکت UDP نباشد یا مقصدش پورت 53 نباشد، null برمی‌گرداند.
     */
    fun tryParseDnsQuery(packet: ByteArray, length: Int): ParsedDnsQuery? {
        if (length < 20) return null
        val versionAndIhl = packet[0].toInt() and 0xFF
        val version = versionAndIhl shr 4
        if (version != 4) return null // فقط IPv4 پشتیبانی می‌شود

        val ihl = (versionAndIhl and 0x0F) * 4
        if (length < ihl + 8) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null // فقط UDP

        val srcIp = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val dstIp = InetAddress.getByAddress(packet.copyOfRange(16, 20))

        val udpOffset = ihl
        val srcPort = ((packet[udpOffset].toInt() and 0xFF) shl 8) or (packet[udpOffset + 1].toInt() and 0xFF)
        val dstPort = ((packet[udpOffset + 2].toInt() and 0xFF) shl 8) or (packet[udpOffset + 3].toInt() and 0xFF)
        val udpLength = ((packet[udpOffset + 4].toInt() and 0xFF) shl 8) or (packet[udpOffset + 5].toInt() and 0xFF)

        if (dstPort != 53) return null // فقط ترافیک DNS

        val dnsOffset = udpOffset + 8
        val dnsLength = udpLength - 8
        if (dnsLength <= 12 || dnsOffset + dnsLength > length) return null

        val dnsPayload = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        val domain = parseDomainFromDnsQuery(dnsPayload) ?: return null

        return ParsedDnsQuery(srcIp, srcPort, dstIp, dstPort, domain, dnsPayload)
    }

    /** خواندن نام دامنه از بخش Question یک بسته DNS استاندارد */
    private fun parseDomainFromDnsQuery(dns: ByteArray): String? {
        if (dns.size < 12) return null
        val qdCount = ((dns[4].toInt() and 0xFF) shl 8) or (dns[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        var pos = 12
        val sb = StringBuilder()
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            pos++
            if (pos + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, pos, len, Charsets.US_ASCII))
            pos += len
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * پاسخ DNS دریافت‌شده از سرور واقعی را دوباره در قالب یک پکت IPv4+UDP بسته‌بندی می‌کند
     * تا از طریق رابط TUN به برنامه مبدا بازگردانده شود (src و dst برعکس پرسش اصلی هستند).
     */
    fun buildResponsePacket(query: ParsedDnsQuery, dnsResponse: ByteArray): ByteArray {
        val udpLength = 8 + dnsResponse.size
        val totalLength = 20 + udpLength
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)

        // --- IPv4 Header ---
        buffer.put(0, (0x45).toByte())           // نسخه 4 + IHL=5
        buffer.put(1, 0)                          // ToS
        buffer.putShort(2, totalLength.toShort()) // طول کل
        buffer.putShort(4, 0)                     // Identification
        buffer.putShort(6, 0)                     // Flags/Fragment
        buffer.put(8, 64)                          // TTL
        buffer.put(9, 17)                          // پروتکل UDP
        buffer.putShort(10, 0)                    // چک‌سام (بعدا محاسبه می‌شود)
        buffer.put(12, query.dstIp.address)        // IP مبدا پاسخ = همان IP فرضی DNS ما
        buffer.put(16, query.srcIp.address)        // IP مقصد پاسخ = برنامه درخواست‌دهنده

        val ipChecksum = calculateChecksum(buffer.array(), 0, 20)
        buffer.putShort(10, ipChecksum.toShort())

        // --- UDP Header ---
        val udpOffset = 20
        buffer.putShort(udpOffset, query.dstPort.toShort())     // src port = 53
        buffer.putShort(udpOffset + 2, query.srcPort.toShort()) // dst port = پورت برنامه
        buffer.putShort(udpOffset + 4, udpLength.toShort())
        buffer.putShort(udpOffset + 6, 0) // چک‌سام UDP اختیاری است، صفر می‌گذاریم

        // --- DNS Payload ---
        buffer.position(udpOffset + 8)
        buffer.put(dnsResponse)

        return buffer.array()
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (length % 2 == 1) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
