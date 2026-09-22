import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.nio.file.*;
import java.util.*;

/** Independent upstream Java readback of a Store-produced signed head. */
public final class StoreHeadCheck {
    public static void main(String[] args) throws Exception {
        try {
            SignedMetadataVerifier verifier = new SignedMetadataVerifier();
            TrustPolicy trust = verifier.readTrustPolicy(Files.readAllBytes(Paths.get(args[0])));
            ShellPolicy policy = new ShellPolicy(trust.applicationId, args[1], trust, args[2], "stable",
                Authentication.APK_KEY, Bootstrap.EMPTY, true, false, 1, Collections.emptyMap(), new byte[0]);
            RequestScope scope = new RequestScope(trust.applicationId, args[1], "stable", 30, Collections.singletonList("x86_64"), 1);
            verifier.verifyHead(Files.readAllBytes(Paths.get(args[3])), policy, scope);
        } catch (Exception invalid) {
            System.err.println("Store head failed upstream verification");
            System.exit(1);
        }
    }
}
