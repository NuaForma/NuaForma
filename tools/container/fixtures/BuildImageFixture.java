import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Reproducible synthetic JPEG author; not a production or native-check dependency. */
public class BuildImageFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply the explicit Base64 output file");
        BufferedImage image = new BufferedImage(96, 48, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(0, 140, 150)); graphics.fillRect(0, 0, 96, 48);
        graphics.setColor(new Color(20, 40, 75)); graphics.fillRect(24, 12, 48, 24); graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpeg", bytes)) throw new IllegalStateException("JPEG encoder unavailable");
        image.flush();
        Files.writeString(Path.of(args[0]), Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\n");
    }
}
