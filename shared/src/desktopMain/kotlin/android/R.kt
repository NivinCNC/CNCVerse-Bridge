package android

/**
 * android.R stub. Plugins read framework resource constants
 * (R.style.Theme.Material.Dialog etc.) through GETSTATIC; without this class
 * those settings-dialog constructors die with NoClassDefFoundError.
 */
class R {
    class style {
        class Theme {
            class Material {
                companion object {
                    @JvmField val Dialog: Int = 0x01030117
                    @JvmField val DialogWhenLarge: Int = 0x01030118
                    @JvmField val DialogNoTitle: Int = 0x01030119
                    @JvmField val DialogAlert: Int = 0x01030cb8
                    @JvmField val Light: Int = 0x01030114
                    @JvmField val NoTitleBar: Int = 0x01030109
                    @JvmField val NoActionBar: Int = 0x01030cb6
                }
            }

            class DeviceDefault {
                companion object {
                    @JvmField val Dialog: Int = 0x01030127
                }
            }

            class Holo {
                companion object {
                    @JvmField val Dialog: Int = 0x0103010a
                    @JvmField val DialogWhenLarge: Int = 0x0103010b
                    @JvmField val DialogNoTitle: Int = 0x0103010c
                    @JvmField val DialogAlert: Int = 0x01030cb7
                    @JvmField val Light: Int = 0x01030107
                }
            }

            companion object {
                @JvmField val Black: Int = 0x010300f5
                @JvmField val BlackNoTitleBar: Int = 0x010300f6
                @JvmField val NoTitleBar: Int = 0x01030109
                @JvmField val NoActionBar: Int = 0x01030cb6
            }
        }

        class Animation {
            companion object {
                @JvmField val Toast: Int = 0x01030105
                @JvmField val Activity: Int = 0x01030103
                @JvmField val Dialog: Int = 0x01030104
            }
        }

        companion object {
            @JvmField val TextAppearanceSmall: Int = 0x01030164
            @JvmField val TextAppearanceMedium: Int = 0x01030162
            @JvmField val TextAppearanceLarge: Int = 0x01030161
        }
    }

    class id {
        companion object {
            @JvmField val icon: Int = 0x0102000d
            @JvmField val title: Int = 0x01030102
            @JvmField val message: Int = 0x01030148
            @JvmField val text1: Int = 0x01030142
            @JvmField val text2: Int = 0x01030143
            @JvmField val button1: Int = 0x0103000e
            @JvmField val button2: Int = 0x0103000f
            @JvmField val button3: Int = 0x01030010
            @JvmField val custom: Int = 0x0103018d
            @JvmField val list: Int = 0x0103005d
            @JvmField val content: Int = 0x01030026
            @JvmField val selectAll: Int = 0x01030114
            @JvmField val cut: Int = 0x01030131
            @JvmField val copy: Int = 0x01030130
            @JvmField val paste: Int = 0x0103013e
            @JvmField val background: Int = 0x01030000
            @JvmField val checkbox: Int = 0x01030110
        }
    }

    class string {
        companion object {
            @JvmField val ok: Int = 0x01040013
            @JvmField val cancel: Int = 0x0104000a
            @JvmField val yes: Int = 0x01040014
            @JvmField val no: Int = 0x0104000d
        }
    }

    class drawable {
        companion object {
            @JvmField val ic_menu_manage: Int = 0x01080058
            @JvmField val ic_menu_settings: Int = 0x0108005e
            @JvmField val ic_menu_save: Int = 0x0108005b
            @JvmField val ic_menu_close_clear_cancel: Int = 0x0108004e
            @JvmField val ic_delete: Int = 0x01080028
            @JvmField val ic_menu_add: Int = 0x01080054
            @JvmField val ic_menu_edit: Int = 0x01080056
        }
    }

    class layout {
        companion object {
            @JvmField val list_content: Int = 0x0109001f
            @JvmField val simple_list_item_1: Int = 0x01090046
            @JvmField val simple_list_item_single_choice: Int = 0x01090048
            @JvmField val simple_list_item_multiple_choice: Int = 0x01090047
            @JvmField val test_list_item: Int = 0x0109005f
        }
    }
}
