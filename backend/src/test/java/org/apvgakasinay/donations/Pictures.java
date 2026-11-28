package org.apvgakasinay.donations;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Pictures made for the tests: a screenshot-like image, and the same file carrying what a phone's camera adds. */
public final class Pictures {

    private Pictures() {
    }

    /** A picture whose left half is red and right half is blue, so its orientation can be told from its pixels. */
    public static BufferedImage halves(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, width / 2, height);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(width / 2, 0, width - width / 2, height);
        graphics.dispose();
        return image;
    }

    public static byte[] jpeg(int width, int height) {
        return encode(halves(width, height), "jpeg");
    }

    public static byte[] png(int width, int height) {
        return encode(halves(width, height), "png");
    }

    public static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    public static BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    /**
     * The JPEG with an EXIF block inserted after its first marker, as a camera writes one: an
     * orientation, a camera maker, and text standing for a position. Little-endian TIFF.
     */
    public static byte[] withExif(byte[] jpeg, int orientation, String marker) {
        byte[] make = (marker + "\0").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        // Header: "II", 42, offset of the first directory (8).
        tiff.writeBytes(new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0});
        // Two entries: Make (0x010f, ASCII) and Orientation (0x0112, SHORT).
        tiff.writeBytes(new byte[] {2, 0});
        int makeOffset = 8 + 2 + 2 * 12 + 4;
        tiff.writeBytes(new byte[] {0x0f, 0x01, 2, 0});
        tiff.writeBytes(le(make.length));
        tiff.writeBytes(le(makeOffset));
        tiff.writeBytes(new byte[] {0x12, 0x01, 3, 0});
        tiff.writeBytes(le(1));
        tiff.writeBytes(new byte[] {(byte) orientation, 0, 0, 0});
        tiff.writeBytes(le(0));
        tiff.writeBytes(make);
        byte[] body = tiff.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        int length = 2 + 6 + body.length;
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xe1, (byte) (length >> 8), (byte) length, 'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(body);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] le(int value) {
        return new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    /** True when the bytes hold the text anywhere. */
    public static boolean contains(byte[] bytes, String text) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(text);
    }

    /** Red, blue, or other: the colour of one pixel, allowing for what JPEG does to colours. */
    public static String colour(BufferedImage image, int x, int y) {
        Color colour = new Color(image.getRGB(x, y));
        if (colour.getRed() > 180 && colour.getBlue() < 90) {
            return "red";
        }
        if (colour.getBlue() > 180 && colour.getRed() < 90) {
            return "blue";
        }
        return "other";
    }
}
