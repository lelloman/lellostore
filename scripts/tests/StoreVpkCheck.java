import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.nio.file.*;
import java.util.*;

/** Independent full-archive comparison; this policy is a test input, not APK pinning. */
public final class StoreVpkCheck {
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        SignedMetadataVerifier metadata = new SignedMetadataVerifier();
        TrustPolicy trust = metadata.readTrustPolicy(Files.readAllBytes(Paths.get(args[1])));
        Map<String,String> reservations = (Map<String,String>)(Map<?,?>)StrictJson.parse(Files.readAllBytes(Paths.get(args[3])), 16 * 1024 * 1024);
        ShellPolicy policy = new ShellPolicy(trust.applicationId, args[2], trust, "https://store.test/", "stable",
            Authentication.PUBLIC, Bootstrap.EMBEDDED, false, false, 1, reservations, new byte[0]);
        RequestScope scope = new RequestScope(trust.applicationId, args[2], "stable", 30, Arrays.asList("x86_64", "arm64-v8a", "armeabi-v7a", "x86"), 1);
        try { new CompleteVpkVerifier().verifyEmbedded(Paths.get(args[0]).toFile(), policy, scope); }
        catch (ContractException rejected) { System.err.println("VPK rejected"); System.exit(1); }
    }
}
