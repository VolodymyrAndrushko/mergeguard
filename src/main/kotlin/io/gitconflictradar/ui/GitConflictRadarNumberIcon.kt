package io.gitconflictradar.ui

import javax.swing.Icon
import com.intellij.util.ui.JBImageIcon
import com.intellij.ui.JBColor
import java.awt.image.BufferedImage

fun createNumberIcon(count: Int, isReal: Boolean = false): Icon {
    val text = count.toString()
    val isZero = count == 0

    // Measure text width using a temporary graphics context
    val tempImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val tempG = tempImage.createGraphics()

    var fontSize = 18
    var font = java.awt.Font("sans-serif", java.awt.Font.BOLD, fontSize)
    tempG.font = font
    var fm = tempG.fontMetrics
    var textWidth = fm.stringWidth(text)

    // Scale down the font if the number is very large (e.g. 124) and exceeds safe icon width limits
    val maxIconWidth = 32
    while (textWidth > maxIconWidth - 4 && fontSize > 9) {
        fontSize--
        font = java.awt.Font("sans-serif", java.awt.Font.BOLD, fontSize)
        tempG.font = font
        fm = tempG.fontMetrics
        textWidth = fm.stringWidth(text)
    }
    tempG.dispose()

    val height = 20
    val width = (textWidth + 6).coerceAtLeast(20)

    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        g.font = font
        val fontMetrics = g.fontMetrics
        val drawX = (width - textWidth) / 2
        val drawY = (height - fontMetrics.height) / 2 + fontMetrics.ascent

        if (isZero) {
            // Draw grey 0
            g.color = JBColor(0x777777, 0x888888)
        } else if (isReal) {
            // Draw red number
            g.color = JBColor(0xEF4444, 0xF87171)
        } else {
            // Draw orange number
            g.color = JBColor(0xD97706, 0xFFB74D)
        }
        g.drawString(text, drawX, drawY)
    } finally {
        g.dispose()
    }

    return JBImageIcon(image)
}
