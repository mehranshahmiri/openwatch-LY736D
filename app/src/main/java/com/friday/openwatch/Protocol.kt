package com.friday.openwatch

import java.util.Calendar
import java.util.UUID

/**
 * Reverse-engineered from xfkj.fitpro.bluetooth.{Profile,SendData} (LaxaFit Pro /
 * FitPro / HiWatch Plus APK) and validated against real hardware (LJ736d / JQ7011 board).
 */
object Protocol {
    val UART_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9d")
    val UART_WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9d")
    val UART_NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9d")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Standard BLE Battery Service - not proprietary, same on any BLE device.
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    // icon codes for the notification-push payload (index into the app's hardcoded set)
    object Icon {
        const val SMS = 1
        const val QQ = 2
        const val WECHAT = 3
        const val FACEBOOK = 4
        const val TWITTER = 5
        const val SKYPE = 6
        const val LINE = 7
        const val WHATSAPP = 8
        const val KAKAOTALK = 9
        const val INSTAGRAM = 16
        const val LINKEDIN = 17
    }

    /** Maps an Android notification's source package to the nearest supported watch icon. */
    fun iconForPackage(pkg: String): Int = when {
        pkg.contains("whatsapp") -> Icon.WHATSAPP
        pkg == "com.tencent.mm" -> Icon.WECHAT
        pkg == "com.tencent.mobileqq" -> Icon.QQ
        pkg.contains("facebook") || pkg == "com.facebook.orca" -> Icon.FACEBOOK
        pkg == "com.twitter.android" || pkg == "com.x.android" -> Icon.TWITTER
        pkg.contains("skype") -> Icon.SKYPE
        pkg == "jp.naver.line.android" -> Icon.LINE
        pkg.contains("kakao") -> Icon.KAKAOTALK
        pkg == "com.instagram.android" -> Icon.INSTAGRAM
        pkg.contains("linkedin") -> Icon.LINKEDIN
        pkg.contains("mms") || pkg.contains("messaging") || pkg.contains("sms") -> Icon.SMS
        else -> Icon.SMS // generic fallback: best-supported "plain text" category
    }

    private fun getProtocol(cmd: Int, key: Int, payload: ByteArray): ByteArray {
        val totalLen = 8 + payload.size
        val pkt = ByteArray(totalLen)
        pkt[0] = 0xCD.toByte()
        val lengthField = totalLen - 3
        pkt[1] = ((lengthField shr 8) and 0xFF).toByte()
        pkt[2] = (lengthField and 0xFF).toByte()
        pkt[3] = cmd.toByte()
        pkt[4] = 0x01
        pkt[5] = key.toByte()
        pkt[6] = ((payload.size shr 8) and 0xFF).toByte()
        pkt[7] = (payload.size and 0xFF).toByte()
        payload.copyInto(pkt, 8)
        return pkt
    }

    /** Sent once immediately after every connect, before anything else. */
    fun pair(): ByteArray = byteArrayOf(0xCD.toByte(), 0, 6, 18, 1, 10, 0, 1, 2)

    /**
     * Sent ONLY on first-ever setup. Resends soft-reset the watch UI back to its
     * default face and clear on-watch style settings, confirmed empirically.
     */
    fun bind(): ByteArray = byteArrayOf(0xCD.toByte(), 0, 2, 19, 1)

    /** 12-byte on/off array: CALL,SMS,WECHAT,QQ,FACEBOOK,TWITTER,SKYPE,LINE,WHATSAPP,KAKAOTALK,INSTAGRAM,LINKEDIN */
    // Confirmed against a real device capture: this watch uses the 11-byte
    // variant (no trailing LinkedIn byte) - cd0010120107000b0001...
    fun enableAllPushCategories(): ByteArray = getProtocol(0x12, 0x07, ByteArray(11) { 1 })

    fun setTime(cal: Calendar = Calendar.getInstance()): ByteArray {
        val second = cal.get(Calendar.SECOND)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val value = second or
            ((year - 2000) shl 26) or
            (month shl 22) or
            (day shl 17) or
            (hour shl 12) or
            (minute shl 6)
        val payload = byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
        return getProtocol(0x12, 0x01, payload)
    }

    /** text is truncated to what the watch's display buffer can hold (~9-20 chars depending on firmware). */
    fun pushNotification(text: String, iconCode: Int): ByteArray {
        val prefix = byteArrayOf(iconCode.toByte(), 0, 0)
        val body = text.toByteArray(Charsets.UTF_8)
        return getProtocol(0x12, 0x12, prefix + body)
    }

    fun findWatch(enable: Boolean): ByteArray =
        byteArrayOf(0xCD.toByte(), 0, 6, 18, 1, 11, 0, 1, if (enable) 1 else 0)

    /** Confirmed from source: SendData.getSetStepValue() - cmd=0x12, key=0x03, 4-byte BE step goal. */
    fun setStepGoal(steps: Int): ByteArray {
        val payload = byteArrayOf(
            ((steps shr 24) and 0xFF).toByte(),
            ((steps shr 16) and 0xFF).toByte(),
            ((steps shr 8) and 0xFF).toByte(),
            (steps and 0xFF).toByte(),
        )
        return getProtocol(0x12, 0x03, payload)
    }

    object Dial {
        /**
         * Watchface (dial) update. Reverse-engineered from TWO captured real transfers
         * (LaxaFit Pro -> real watch, both verified working - a preset theme push and
         * a custom-photo push):
         *  - START (key=2), 5-byte payload `XX 00 ff ff ff`. XX=0x01 for a preset theme,
         *    XX=0x04 for a custom photo (confirmed from the two captures - this is the
         *    "source type" byte). Sent once is sufficient (the preset-theme capture sent
         *    it twice ~3.5s apart, likely just a UI-triggered resend, not a hard requirement).
         *  - FILE chunk (key=1) payload = [seq_hi][seq_lo][raw data...], NO per-chunk checksum.
         *  - FINISH (key=3) payload = [totalLength 4B BE][totalChecksum 4B BE, 32-bit
         *    running sum over every byte of the transferred file, mod 2^32].
         *  - The transferred "file" for algorithm 2 (confirmed via this watch's own local
         *    DB: LJ736 / 240x296 / ALGORITHM=2) is: [font binary] + [6-byte proprietary
         *    header `1601` + width_LE(2B) + height_LE(2B) + `00000a00`] + [RGB565 pixel
         *    data, transformed by rotatDerection() - see WatchfaceConverter]. Confirmed by
         *    reassembling a real capture: the first 3257 bytes matched a pulled font.bin
         *    exactly.
         */
        const val CHUNK_DATA_SIZE = 202
        const val SOURCE_TYPE_CUSTOM_PHOTO = 0x04
        const val SOURCE_TYPE_PRESET_THEME = 0x01

        private const val CMD_DIAL_UPDATE = 31
        private const val KEY_START = 2
        private const val KEY_FILE = 1
        private const val KEY_FINISH = 3

        fun updateStart(sourceType: Int = SOURCE_TYPE_CUSTOM_PHOTO): ByteArray =
            getProtocol(CMD_DIAL_UPDATE, KEY_START, byteArrayOf(sourceType.toByte(), 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))

        fun updateChunk(seq: Int, chunk: ByteArray): ByteArray {
            val payload = ByteArray(2 + chunk.size)
            payload[0] = ((seq shr 8) and 0xFF).toByte()
            payload[1] = (seq and 0xFF).toByte()
            chunk.copyInto(payload, 2)
            return getProtocol(CMD_DIAL_UPDATE, KEY_FILE, payload)
        }

        fun updateFinish(totalLength: Int, totalChecksum: Int): ByteArray {
            val payload = ByteArray(8)
            payload[0] = ((totalLength shr 24) and 0xFF).toByte()
            payload[1] = ((totalLength shr 16) and 0xFF).toByte()
            payload[2] = ((totalLength shr 8) and 0xFF).toByte()
            payload[3] = (totalLength and 0xFF).toByte()
            payload[4] = ((totalChecksum shr 24) and 0xFF).toByte()
            payload[5] = ((totalChecksum shr 16) and 0xFF).toByte()
            payload[6] = ((totalChecksum shr 8) and 0xFF).toByte()
            payload[7] = (totalChecksum and 0xFF).toByte()
            return getProtocol(CMD_DIAL_UPDATE, KEY_FINISH, payload)
        }
    }
}
