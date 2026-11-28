package org.apvgakasinay.donations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import org.apvgakasinay.donations.Pictures;
import org.junit.jupiter.api.Test;

/**
 * Scenarios 3.8 to 3.10 where they need no database: what the server writes from the pixels of
 * a screenshot, and what does not survive it (contract/donation-lifecycle.md, 4.3).
 */
class EvidenceImagesTest {

    @Test
    void d3_8_theStoredPictureIsWrittenAgainFromPixelsAndScaledToTheLimit() throws Exception {
        byte[] sent = Pictures.jpeg(4000, 3000);
        EvidenceImages.Encoded stored = EvidenceImages.reencode(sent);
        assertThat(stored.format()).isEqualTo("jpeg");
        assertThat(stored.width()).isEqualTo(2400);
        assertThat(stored.height()).isEqualTo(1800);
        assertThat(stored.bytes()).isNotEqualTo(sent);
        BufferedImage decoded = Pictures.decode(stored.bytes());
        assertThat(decoded.getWidth()).isEqualTo(2400);
        assertThat(Pictures.colour(decoded, 100, 900)).isEqualTo("red");
        assertThat(Pictures.colour(decoded, 2300, 900)).isEqualTo("blue");

        // A phone's screenshot is within the limit: it keeps its size, and is still written again.
        byte[] screenshot = Pictures.png(1170, 2532);
        EvidenceImages.Encoded png = EvidenceImages.reencode(screenshot);
        assertThat(png.format()).isEqualTo("png");
        assertThat(png.width()).isEqualTo(1109);
        assertThat(png.height()).isEqualTo(2400);
        EvidenceImages.Encoded small = EvidenceImages.reencode(Pictures.png(390, 844));
        assertThat(small.width()).isEqualTo(390);
        assertThat(small.height()).isEqualTo(844);
    }

    @Test
    void d3_9_locationAndDeviceDataAreDroppedAndTheOrientationIsAppliedToThePixels() throws Exception {
        byte[] plain = Pictures.jpeg(400, 200);
        // Orientation 6: the stored rows are the picture turned a quarter turn; upright it is 200 wide and 400 high.
        byte[] camera = Pictures.withExif(plain, 6, "SampleCameraMaker GPS 14.5N 5.9E");
        assertThat(Pictures.contains(camera, "SampleCameraMaker")).isTrue();
        assertThat(EvidenceImages.orientation(camera)).isEqualTo(6);
        assertThat(EvidenceImages.orientation(plain)).isEqualTo(1);

        EvidenceImages.Encoded stored = EvidenceImages.reencode(camera);
        assertThat(stored.width()).isEqualTo(200);
        assertThat(stored.height()).isEqualTo(400);
        assertThat(Pictures.contains(stored.bytes(), "SampleCameraMaker")).isFalse();
        assertThat(Pictures.contains(stored.bytes(), "Exif")).isFalse();
        assertThat(Pictures.contains(stored.bytes(), "GPS")).isFalse();
        // Turned a quarter turn clockwise: what was on the left (red) is now at the top.
        BufferedImage upright = Pictures.decode(stored.bytes());
        assertThat(Pictures.colour(upright, 100, 40)).isEqualTo("red");
        assertThat(Pictures.colour(upright, 100, 360)).isEqualTo("blue");
        // The stored file states no orientation: its pixels are already upright.
        assertThat(EvidenceImages.orientation(stored.bytes())).isEqualTo(1);
    }

    @Test
    void d3_9_everyOrientationIsTurnedUpright() throws Exception {
        byte[] plain = Pictures.jpeg(400, 200);
        // For each EXIF value: the size upright, and where red (the stored left half) ends up.
        Object[][] expected = {
                {1, 400, 200, "left"}, {2, 400, 200, "right"}, {3, 400, 200, "right"}, {4, 400, 200, "left"},
                {5, 200, 400, "top"}, {6, 200, 400, "top"}, {7, 200, 400, "bottom"}, {8, 200, 400, "bottom"}};
        for (Object[] row : expected) {
            EvidenceImages.Encoded stored = EvidenceImages.reencode(Pictures.withExif(plain, (int) row[0], "Maker"));
            assertThat(stored.width()).as("orientation " + row[0]).isEqualTo(row[1]);
            assertThat(stored.height()).as("orientation " + row[0]).isEqualTo(row[2]);
            BufferedImage image = Pictures.decode(stored.bytes());
            int w = image.getWidth();
            int h = image.getHeight();
            String red = switch ((String) row[3]) {
                case "left" -> Pictures.colour(image, w / 8, h / 2);
                case "right" -> Pictures.colour(image, w - w / 8, h / 2);
                case "top" -> Pictures.colour(image, w / 2, h / 8);
                default -> Pictures.colour(image, w / 2, h - h / 8);
            };
            assertThat(red).as("orientation " + row[0] + ": red at the " + row[3]).isEqualTo("red");
        }
    }

    @Test
    void d3_10_anythingAppendedToAPictureDoesNotSurvive() throws Exception {
        byte[] picture = Pictures.png(300, 300);
        byte[] trailer = "<script>alert('appended')</script>PK\u0003\u0004 a second file".getBytes(StandardCharsets.ISO_8859_1);
        byte[] both = new byte[picture.length + trailer.length];
        System.arraycopy(picture, 0, both, 0, picture.length);
        System.arraycopy(trailer, 0, both, picture.length, trailer.length);

        EvidenceImages.Encoded stored = EvidenceImages.reencode(both);
        assertThat(Pictures.contains(stored.bytes(), "appended")).isFalse();
        assertThat(Pictures.contains(stored.bytes(), "second file")).isFalse();
        assertThat(Pictures.decode(stored.bytes()).getWidth()).isEqualTo(300);
    }

    @Test
    void d3_3_onlyAJpegOrAPngDecidedFromItsContentIsAccepted() {
        byte[] gif = "GIF89a\u0001\u0000\u0001\u0000\u0080\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);
        byte[] pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] text = "This is a text file renamed screenshot.png".getBytes(StandardCharsets.UTF_8);
        byte[] truncated = java.util.Arrays.copyOf(Pictures.png(300, 300), 40);
        byte[] headerOnly = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F', 0};
        for (byte[] refused : new byte[][] {gif, pdf, text, truncated, headerOnly, new byte[0]}) {
            assertThatThrownBy(() -> EvidenceImages.reencode(refused)).isInstanceOf(EvidenceImages.Invalid.class);
        }
        assertThat(EvidenceImages.sniff(Pictures.jpeg(10, 10))).isEqualTo("jpeg");
        assertThat(EvidenceImages.sniff(Pictures.png(10, 10))).isEqualTo("png");
        assertThat(EvidenceImages.sniff(gif)).isNull();
    }

    @Test
    void d3_3_aPictureLargerThanFortyMillionPixelsIsRefusedBeforeItIsDecoded() {
        // 7000 x 6000 is 42 million pixels. A flat picture of that size is a small file.
        byte[] huge = Pictures.encode(new BufferedImage(7000, 6000, BufferedImage.TYPE_BYTE_BINARY), "png");
        assertThat(huge.length).isLessThan(5 * 1024 * 1024);
        assertThatThrownBy(() -> EvidenceImages.reencode(huge)).isInstanceOf(EvidenceImages.Invalid.class).hasMessageContaining("size");
    }

    @Test
    void theIdentifiersAndCodesHaveTheFormTheContractGives() {
        for (int i = 0; i < 200; i++) {
            assertThat(Codes.newReference()).matches("^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$");
            assertThat(Codes.publicId("don_")).matches("^don_[A-Za-z0-9]{22}$");
        }
        // Eight symbols are five bytes, both ways.
        byte[] bytes = {0x01, 0x23, 0x45, 0x67, (byte) 0x89};
        String symbols = Codes.encode(bytes, 8);
        assertThat(Codes.decode(symbols)).isEqualTo(bytes);
        assertThat(Codes.encode(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}, 8)).isEqualTo("ZZZZZZZZ");
        assertThat(Codes.normalize("7k4m-2qxd", 8)).isEqualTo("7K4M2QXD");
        // The look-alike letters are not in the alphabet.
        assertThat(Codes.normalize("ILOU-ILOU", 8)).isNull();
        assertThat(Codes.normalize("7K4M-2QX", 8)).isNull();
    }
}
