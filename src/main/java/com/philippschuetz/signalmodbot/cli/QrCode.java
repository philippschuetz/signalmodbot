package com.philippschuetz.signalmodbot.cli;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class QrCode {
    private QrCode() {}

    public static void printToTerminal(String content) throws IOException {
        try {
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 4));
            // UTF-8 explicitly: a container without a locale would print the block characters as '?'
            PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
            for (int y = 0; y < m.getHeight(); y += 2) {
                StringBuilder line = new StringBuilder();
                for (int x = 0; x < m.getWidth(); x++) {
                    boolean top = m.get(x, y);
                    boolean bottom = y + 1 < m.getHeight() && m.get(x, y + 1);
                    line.append("\u001B[").append(top ? "30" : "97").append(';')
                            .append(bottom ? "40" : "107").append("m▀");
                }
                line.append("\u001B[0m");
                out.println(line);
            }
            out.flush();
        } catch (Exception e) {
            throw new IOException("Failed to render QR code", e);
        }
    }
}