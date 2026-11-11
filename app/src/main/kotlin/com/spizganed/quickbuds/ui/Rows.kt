package com.spizganed.quickbuds.ui

import android.content.Context
import com.spizganed.quickbuds.bluetooth.PhoneSpatial
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.RowRules

/** The rows of the connected buds, for screens, the widget and the tiles. No Activity needed. */
object Rows {
    /**
     * The rows the buds' own answers support ([RowRules]). Nothing read yet = only the
     * unconditional rows; the last saved reply shows its rows until the fresh one arrives.
     */
    fun of(c: Context): Set<String> = RowRules.rows(
        ModelCatalog.current(c)?.json,
        Capabilities.reply(c),
        Capabilities.commands(c),
        AncModes.of(c).slots().takeIf { it.isNotEmpty() },
        PhoneSpatial.supported(c),
        manual = ModelCatalog.manual(c) != null,
        gestures = !GestureModel.of(c).isEmpty
    )
}
