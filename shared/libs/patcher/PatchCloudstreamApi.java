import org.objectweb.asm.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Rewrites call sites in cloudstream-api.jar and NiceHttp to the bridge's faster/quieter versions.
 * See ../README.md. Fails unless every rule whose class is in the jar patches the expected number
 * of call sites.
 *
 * Usage: java -cp asm.jar:. PatchCloudstreamApi <in.jar> <out.jar>
 */
public class PatchCloudstreamApi {
    record Rule(String clazz, int op, String owner, String name, String desc,
                int newOp, String newOwner, String newName, String newDesc, int expected) {}

    static final List<Rule> RULES = List.of(
        // loadExtractor's fuzzy fallback: Levenshtein.partialRatio$default -> FastExtractorMatch (same descriptor)
        new Rule("com/lagradost/cloudstream3/utils/ExtractorApiKt.class",
            Opcodes.INVOKESTATIC, "com/lagradost/cloudstream3/utils/Levenshtein", "partialRatio$default",
            "(Lcom/lagradost/cloudstream3/utils/Levenshtein;Ljava/lang/String;Ljava/lang/String;Lkotlin/jvm/functions/Function1;ILjava/lang/Object;)I",
            Opcodes.INVOKESTATIC, "com/cncverse/stremiobridge/plugin/FastExtractorMatch", "partialRatioForExtractor",
            "(Lcom/lagradost/cloudstream3/utils/Levenshtein;Ljava/lang/String;Ljava/lang/String;Lkotlin/jvm/functions/Function1;ILjava/lang/Object;)I",
            1),
        // logError's full stack trace to stderr -> one rate-limited line (same stack effect: pops the Throwable)
        new Rule("com/lagradost/cloudstream3/mvvm/ArchComponentExtKt.class",
            Opcodes.INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace", "()V",
            Opcodes.INVOKESTATIC, "com/cncverse/stremiobridge/plugin/PluginErrorLog", "printStackTrace",
            "(Ljava/lang/Throwable;)V",
            -1), // every printStackTrace in this class (count printed; must be > 0)
        // NiceHttp (vendored as NiceHttp-0.4.16-patched.jar): same treatment for its stack traces
        trace("com/lagradost/nicehttp/ContinuationCallback.class"),
        trace("com/lagradost/nicehttp/NiceResponse.class"),
        trace("com/lagradost/nicehttp/OkioHelper.class")
    );

    static Rule trace(String clazz) {
        // "*": NiceHttp calls it on the concrete type (IOException/Exception/SecurityException.printStackTrace);
        // any Throwable is a valid argument for PluginErrorLog.printStackTrace(Throwable)
        return new Rule(clazz, Opcodes.INVOKEVIRTUAL, "*", "printStackTrace", "()V",
            Opcodes.INVOKESTATIC, "com/cncverse/stremiobridge/plugin/PluginErrorLog", "printStackTrace",
            "(Ljava/lang/Throwable;)V", -1);
    }

    public static void main(String[] args) throws Exception {
        Map<Rule, int[]> counts = new LinkedHashMap<>();
        for (Rule r : RULES) counts.put(r, new int[]{0});
        Set<String> seen = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[1]))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                for (Rule r : RULES) if (e.getName().equals(r.clazz())) { seen.add(r.clazz()); data = patch(data, r, counts.get(r)); }
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(e.getTime());
                out.putNextEntry(ne);
                out.write(data);
                out.closeEntry();
            }
        }
        for (var en : counts.entrySet()) {
            Rule r = en.getKey(); int n = en.getValue()[0];
            if (!seen.contains(r.clazz())) continue; // rule for the other jar
            System.out.println(r.clazz() + ": " + r.owner() + "." + r.name() + " -> " + r.newOwner() + "." + r.newName() + " x" + n);
            if (r.expected() >= 0 ? n != r.expected() : n == 0)
                throw new IllegalStateException("unexpected call-site count for " + r.name() + ": " + n);
        }
    }

    static byte[] patch(byte[] cls, Rule r, int[] count) {
        ClassReader cr = new ClassReader(cls);
        ClassWriter cw = new ClassWriter(cr, 0); // same stack effect per call: frames/max stack unchanged
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, exc)) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                        if (op == r.op() && (r.owner().equals("*") || owner.equals(r.owner())) && mname.equals(r.name()) && mdesc.equals(r.desc())) {
                            count[0]++;
                            super.visitMethodInsn(r.newOp(), r.newOwner(), r.newName(), r.newDesc(), false);
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
