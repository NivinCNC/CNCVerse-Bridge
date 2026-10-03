import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Rewrites cloudstream-api.jar so loadExtractor's fuzzy fallback calls
 * com.cncverse.stremiobridge.plugin.FastExtractorMatch.partialRatioForExtractor
 * instead of Levenshtein.partialRatio$default (same descriptor). See ../README.md.
 *
 * Usage: java -cp asm.jar:. PatchCloudstreamApi <in.jar> <out.jar>
 */
public class PatchCloudstreamApi {
    static final String TARGET = "com/lagradost/cloudstream3/utils/ExtractorApiKt.class";
    static final String OLD_OWNER = "com/lagradost/cloudstream3/utils/Levenshtein";
    static final String OLD_NAME = "partialRatio$default";
    static final String NEW_OWNER = "com/cncverse/stremiobridge/plugin/FastExtractorMatch";
    static final String NEW_NAME = "partialRatioForExtractor";

    public static void main(String[] args) throws Exception {
        int[] patched = {0};
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[1]))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (e.getName().equals(TARGET)) data = patch(data, patched);
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(e.getTime());
                out.putNextEntry(ne);
                out.write(data);
                out.closeEntry();
            }
        }
        System.out.println("patched call sites: " + patched[0]);
        if (patched[0] != 1) throw new IllegalStateException("expected exactly 1 call site, got " + patched[0]);
    }

    static byte[] patch(byte[] cls, int[] patched) {
        ClassReader cr = new ClassReader(cls);
        ClassWriter cw = new ClassWriter(cr, 0); // no frame/stack changes: same descriptor, same opcode
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                        if (op == Opcodes.INVOKESTATIC && owner.equals(OLD_OWNER) && mname.equals(OLD_NAME)) {
                            patched[0]++;
                            super.visitMethodInsn(op, NEW_OWNER, NEW_NAME, mdesc, false);
                        } else {
                            super.visitMethodInsn(op, owner, mname, mdesc, itf);
                        }
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }
}
