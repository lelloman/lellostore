import java.nio.file.*;
import com.lelloman.paravoidandroid.contract.InstalledPolicyCodec;

/** Read-only comparison with the production (non-debuggable) policy decoder. */
public final class StorePolicyCheck {
    public static void main(String[] args) throws Exception {
        InstalledPolicyCodec.read(Files.readAllBytes(Path.of(args[0])), false);
    }
}
