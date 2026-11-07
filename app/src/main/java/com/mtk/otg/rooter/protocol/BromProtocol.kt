package com.mtk.otg.rooter.protocol

object BromProtocol {
    // מזהי USB של מצב BROM (Preloader) של MediaTek
    const val MTK_VID = 0x0E8D
    const val BROM_PID = 0x0003
    const val PRELOADER_PID = 0x2000

    // פקודות בסיסיות של פרוטוקול BROM
    const val CMD_SEND_DA = 0xD7
    const val CMD_JUMP_DA = 0xD5
    const val CMD_GET_TARGET_CONFIG = 0xD8
    const val CMD_GET_BROM_VERSION = 0xFE
    
    // פקודות קריאה/כתיבה (משמשות אחרי שה-DA רץ)
    const val CMD_READ16 = 0xD0
    const val CMD_READ32 = 0xD1
    const val CMD_WRITE16 = 0xD2
    const val CMD_WRITE32 = 0xD4

    // ערכי קסם (Magic Numbers) לשליחת ה-DA
    // הערך הזה מאותת ל-BROM שמגיע קובץ DA תקין
    const val DA_MAGIC = 0x58881688
    
    // כתובת ברירת המחדל לטעינת ה-DA בזיכרון ה-SRAM של MT6765
    const val DA_LOAD_ADDRESS = 0x201000

    // ערכי Handshake ראשוניים (תלוי גרסת BROM, לרוב 0xA0)
    const val HANDSHAKE_BYTE = 0xA0
}
