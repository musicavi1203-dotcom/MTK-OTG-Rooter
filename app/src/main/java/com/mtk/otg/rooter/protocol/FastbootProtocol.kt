package com.mtk.otg.rooter.protocol

object FastbootProtocol {
    // פקודות בסיסיות של Fastboot (נשלחות כטקסט מסתיים ב-Null)
    const val CMD_GETVAR = "getvar:"
    const val CMD_DOWNLOAD = "download:"
    const val CMD_FLASH = "flash:"
    const val CMD_REBOOT = "reboot"
    const val CMD_REBOOT_BOOTLOADER = "reboot-bootloader"
    const val CMD_OEM = "oem "

    // תגובות אפשריות מהמכשיר
    const val RESPONSE_OKAY = "OKAY"
    const val RESPONSE_FAIL = "FAIL"
    const val RESPONSE_DATA = "DATA"
    const val RESPONSE_INFO = "INFO"

    // גודל חבילה מקסימלי ל-Fastboot (בדרך כלל 64KB או 1MB)
    const val MAX_DOWNLOAD_SIZE = 1024 * 1024 
}
