import com.lelloman.paravoidandroid.contract.*;
import com.lelloman.paravoidandroid.contract.Protocol.*;
import java.io.*;
import java.util.*;

/**
 * Applies a Store-generated DVPK with the upstream bounded shell decoder.
 * Arguments: base patch output baseSha256 baseSize patchSha256 patchSize targetSha256 targetSize.
 * The reconstructed archive still needs ordinary full-VPK verification afterwards.
 */
public final class StoreDvpkCheck {
    public static void main(String[] args) throws Exception {
        String zero = String.join("", Collections.nCopies(64, "0"));
        ExpectedArchive baseIdentity = new ExpectedArchive("base", 1, zero, args[3], Long.parseLong(args[4]));
        ExpectedDelta delta = new ExpectedDelta(DeltaPatch.ALGORITHM, args[3], Long.parseLong(args[4]),
            args[5], Long.parseLong(args[6]));
        ExpectedArchive target = new ExpectedArchive("target", 2, zero, args[7], Long.parseLong(args[8]),
            Collections.singletonList(delta));
        try (RandomAccessFile file = new RandomAccessFile(args[0], "r")) {
            DeltaBase base = new DeltaBase() {
                @Override public ExpectedArchive identity() { return baseIdentity; }
                @Override public int read(long position, byte[] buffer, int offset, int length) throws IOException {
                    file.seek(position);
                    return file.read(buffer, offset, length);
                }
                @Override public void close() {}
            };
            DeltaPatch.apply(new File(args[1]), base, new File(args[2]), delta, target, () -> {});
        } catch (IOException rejected) {
            System.err.println("DVPK rejected by upstream decoder");
            System.exit(1);
        }
    }
}
