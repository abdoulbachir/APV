package org.apvgakasinay.donations.internal;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Turns the bytes someone sent as a screenshot into the only file that is ever stored
 * (contract/donation-lifecycle.md, 4.1 and 4.3), with the Java runtime's own picture support.
 *
 * <p>The format is decided from the content, never from a name or a declared type. The picture
 * is decoded to pixels, its embedded orientation is applied, it is scaled so its longer side is
 * at most 2,400 pixels, and a new JPEG or PNG is written from those pixels. Everything else the
 * file carried does not survive: location, device, dates, colour profiles, text chunks,
 * thumbnails, and anything appended to it. The bytes that were sent are never written anywhere.
 */
final class EvidenceImages {

    static final long MAX_PIXELS = 40_000_000L;
    static final int MAX_SIDE = 2400;
    private static final float JPEG_QUALITY = 0.88f;

    /** The content is not a JPEG or PNG that can be decoded within the limits. */
    static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String why) {
            super(why);
        }
    }

    /** The picture as it is stored. {@code format} is {@code jpeg} or {@code png}. */
    record Encoded(byte[] bytes, String format, int width, int height) {
    }

    private EvidenceImages() {
    }

    /** {@code jpeg}, {@code png}, or null: read from the first bytes of the content. */
    static String sniff(byte[] sent) {
        if (sent.length >= 3 && (sent[0] & 0xff) == 0xff && (sent[1] & 0xff) == 0xd8 && (sent[2] & 0xff) == 0xff) {
            return "jpeg";
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
        if (sent.length >= png.length && java.util.Arrays.equals(java.util.Arrays.copyOf(sent, png.length), png)) {
            return "png";
        }
        return null;
    }

    static Encoded reencode(byte[] sent) throws Invalid {
        String format = sniff(sent);
        if (format == null) {
            throw new Invalid("not a JPEG or a PNG");
        }
        BufferedImage pixels = decode(sent, format);
        int orientation = format.equals("jpeg") ? orientation(sent) : 1;
        BufferedImage upright = redraw(pixels, orientation, format.equals("png") && pixels.getColorModel().hasAlpha());
        return new Encoded(encode(upright, format), format, upright.getWidth(), upright.getHeight());
    }

    private static BufferedImage decode(byte[] sent, String format) throws Invalid {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        if (!readers.hasNext()) {
            throw new IllegalStateException("this Java runtime cannot read " + format);
        }
        ImageReader reader = readers.next();
        try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(sent))) {
            reader.setInput(in, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
                throw new Invalid("picture size outside the limits");
            }
            // A very large picture is sampled while it is read, so memory follows what is kept, not what was sent.
            int step = Math.max(1, Math.max(width, height) / MAX_SIDE);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(step, step, 0, 0);
            BufferedImage image = reader.read(0, param);
            if (image == null) {
                throw new Invalid("nothing decoded");
            }
            return image;
        } catch (IOException | RuntimeException undecodable) {
            throw new Invalid("cannot be decoded");
        } catch (OutOfMemoryError tooLarge) {
            throw new Invalid("too large to decode");
        } finally {
            reader.dispose();
        }
    }

    /** Draws the pixels upright and at most {@link #MAX_SIDE} on the longer side, into a new picture that holds nothing else. */
    private static BufferedImage redraw(BufferedImage pixels, int orientation, boolean alpha) {
        int width = pixels.getWidth();
        int height = pixels.getHeight();
        boolean turned = orientation >= 5 && orientation <= 8;
        int uprightWidth = turned ? height : width;
        int uprightHeight = turned ? width : height;
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(uprightWidth, uprightHeight));
        int outWidth = Math.max(1, (int) Math.round(uprightWidth * scale));
        int outHeight = Math.max(1, (int) Math.round(uprightHeight * scale));

        AffineTransform transform = AffineTransform.getScaleInstance((double) outWidth / uprightWidth, (double) outHeight / uprightHeight);
        transform.concatenate(upright(orientation, width, height));

        BufferedImage out = new BufferedImage(outWidth, outHeight, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = out.createGraphics();
        try {
            if (!alpha) {
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, outWidth, outHeight);
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(pixels, transform, null);
        } finally {
            graphics.dispose();
        }
        return out;
    }

    /** The transform that turns stored pixels upright for an EXIF orientation value (1 to 8). */
    private static AffineTransform upright(int orientation, int width, int height) {
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> {
                t.scale(-1, 1);
                t.translate(-width, 0);
            }
            case 3 -> {
                t.translate(width, height);
                t.rotate(Math.PI);
            }
            case 4 -> {
                t.scale(1, -1);
                t.translate(0, -height);
            }
            case 5 -> {
                t.rotate(-Math.PI / 2);
                t.scale(-1, 1);
            }
            case 6 -> {
                t.translate(height, 0);
                t.rotate(Math.PI / 2);
            }
            case 7 -> {
                t.scale(-1, 1);
                t.translate(-height, 0);
                t.translate(0, width);
                t.rotate(3 * Math.PI / 2);
            }
            case 8 -> {
                t.translate(0, width);
                t.rotate(3 * Math.PI / 2);
            }
            default -> {
                // 1, or anything that is not an orientation: as stored.
            }
        }
        return t;
    }

    private static byte[] encode(BufferedImage image, String format) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            throw new IllegalStateException("this Java runtime cannot write " + format);
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (format.equals("jpeg")) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            // No metadata is passed: the file holds the pixels and what the format itself requires.
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException impossible) {
            throw new IllegalStateException("cannot encode a picture in memory", impossible);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /**
     * The orientation a JPEG's EXIF block states, 1 to 8, or 1 when it states none. Read from the
     * bytes directly, before they are discarded, so it can be applied to the pixels.
     */
    static int orientation(byte[] jpeg) {
        try {
            int at = 2;
            while (at + 4 <= jpeg.length) {
                if ((jpeg[at] & 0xff) != 0xff) {
                    return 1;
                }
                int marker = jpeg[at + 1] & 0xff;
                if (marker == 0xda || marker == 0xd9) {
                    return 1;
                }
                int length = ((jpeg[at + 2] & 0xff) << 8) | (jpeg[at + 3] & 0xff);
                if (length < 2 || at + 2 + length > jpeg.length) {
                    return 1;
                }
                if (marker == 0xe1 && length >= 16 && jpeg[at + 4] == 'E' && jpeg[at + 5] == 'x' && jpeg[at + 6] == 'i' && jpeg[at + 7] == 'f'
                        && jpeg[at + 8] == 0 && jpeg[at + 9] == 0) {
                    return tiffOrientation(jpeg, at + 10, at + 2 + length);
                }
                at += 2 + length;
            }
        } catch (RuntimeException malformed) {
            // A block that cannot be read states nothing.
        }
        return 1;
    }

    private static int tiffOrientation(byte[] data, int start, int end) {
        boolean little = data[start] == 'I' && data[start + 1] == 'I';
        boolean big = data[start] == 'M' && data[start + 1] == 'M';
        if ((!little && !big) || number(data, start + 2, 2, little) != 42) {
            return 1;
        }
        int directory = start + (int) number(data, start + 4, 4, little);
        if (directory < start || directory + 2 > end) {
            return 1;
        }
        int entries = (int) number(data, directory, 2, little);
        for (int i = 0; i < entries; i++) {
            int entry = directory + 2 + i * 12;
            if (entry + 12 > end) {
                return 1;
            }
            if (number(data, entry, 2, little) == 0x0112) {
                long value = number(data, entry + 8, 2, little);
                return value >= 1 && value <= 8 ? (int) value : 1;
            }
        }
        return 1;
    }

    private static long number(byte[] data, int at, int bytes, boolean little) {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            int b = data[little ? at + bytes - 1 - i : at + i] & 0xff;
            value = (value << 8) | b;
        }
        return value;
    }
}
