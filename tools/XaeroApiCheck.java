import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Lists every Xaero class, method and field the built mod uses, directly or through Mixin annotations,
 * that is missing from a given set of Xaero jars.
 * Usage: java XaeroApiCheck <mod.jar> <xaero.jar>...
 * Prints "OK" or one line per missing member and exits 1.
 */
public final class XaeroApiCheck {
    private final Map<String, ClassNode> xaero = new HashMap<>();
    private final Set<String> missing = new TreeSet<>();

    public static void main(String[] args) throws Exception {
        XaeroApiCheck check = new XaeroApiCheck();
        for (int i = 1; i < args.length; i++) check.load(Path.of(args[i]), check.xaero);
        Map<String, ClassNode> mod = new HashMap<>();
        check.load(Path.of(args[0]), mod);
        for (ClassNode node : mod.values()) check.scan(node);
        if (check.missing.isEmpty()) System.out.println("OK");
        else check.missing.forEach(System.out::println);
        System.exit(check.missing.isEmpty() ? 0 : 1);
    }

    private void load(Path jar, Map<String, ClassNode> into) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
                    into.put(node.name, node);
                }
            }
        }
    }

    private static boolean isXaero(String owner) {
        return owner != null && owner.startsWith("xaero/");
    }

    private void scan(ClassNode node) {
        if (isXaero(node.superName)) requireClass(node.superName, node.name);
        String target = null;
        for (AnnotationNode a : annotations(node.invisibleAnnotations, node.visibleAnnotations)) {
            if (!a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            Object value = get(a, "value"), targets = get(a, "targets");
            if (value instanceof List<?> types && !types.isEmpty()) target = ((Type) types.get(0)).getInternalName();
            if (targets instanceof List<?> names && !names.isEmpty())
                target = ((String) names.get(0)).replace('.', '/');
        }
        if (isXaero(target)) {
            if (!requireClass(target, node.name)) return;
            for (FieldNode field : node.fields)
                if (has(field.invisibleAnnotations, field.visibleAnnotations, "Shadow"))
                    requireField(target, field.name, field.desc, node.name + " @Shadow");
        }
        for (MethodNode method : node.methods) {
            String where = node.name + "." + method.name;
            if (isXaero(target)) mixinMethod(target, method, where);
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && isXaero(call.owner))
                    requireMethod(call.owner, call.name, call.desc, where);
                else if (insn instanceof FieldInsnNode field && isXaero(field.owner))
                    requireField(field.owner, field.name, field.desc, where);
                else if (insn instanceof TypeInsnNode type && isXaero(type.desc)) requireClass(type.desc, where);
            }
        }
    }

    private void mixinMethod(String target, MethodNode method, String where) {
        for (AnnotationNode a : annotations(method.invisibleAnnotations, method.visibleAnnotations)) {
            String kind = a.desc.substring(a.desc.lastIndexOf('/') + 1, a.desc.length() - 1);
            switch (kind) {
                case "Accessor" -> {
                    String name = (String) get(a, "value");
                    Type type = Type.getMethodType(method.desc);
                    String desc = type.getArgumentTypes().length == 1
                            ? type.getArgumentTypes()[0].getDescriptor()
                            : type.getReturnType().getDescriptor();
                    requireField(target, name, desc, where + " @Accessor");
                }
                case "Inject", "WrapOperation", "Redirect", "ModifyArg", "ModifyVariable" -> {
                    @SuppressWarnings("unchecked")
                    List<String> names = (List<String>) get(a, "method");
                    if (names != null && names.stream().noneMatch(name -> methodNamed(target, name)))
                        missing.add(target + "." + names + " (" + where + " @" + kind + ")");
                    for (AnnotationNode at : ats(a)) atTarget(at, where);
                }
                default -> {}
            }
        }
    }

    /** INVOKE and FIELD targets are checked when they name a Xaero member; a list of alternatives needs one match. */
    private void atTarget(AnnotationNode at, String where) {
        String value = (String) get(at, "value"), target = (String) get(at, "target");
        if (target == null || !target.startsWith("Lxaero/")) return;
        String owner = target.substring(1, target.indexOf(';'));
        String rest = target.substring(target.indexOf(';') + 1);
        if ("INVOKE".equals(value)) {
            String name = rest.substring(0, rest.indexOf('(')), desc = rest.substring(rest.indexOf('('));
            if (!desc.contains("Lnet/minecraft/client/") && !desc.contains("Lcom/mojang/")) // named-only alternative
            requireMethod(owner, name, desc, where + " @At");
        } else if ("FIELD".equals(value)) {
            requireField(
                    owner, rest.substring(0, rest.indexOf(':')), rest.substring(rest.indexOf(':') + 1), where + " @At");
        }
    }

    private List<AnnotationNode> ats(AnnotationNode a) {
        Object at = get(a, "at");
        List<AnnotationNode> result = new ArrayList<>();
        if (at instanceof AnnotationNode single) result.add(single);
        else if (at instanceof List<?> list) for (Object o : list) result.add((AnnotationNode) o);
        return result;
    }

    private boolean methodNamed(String owner, String name) {
        for (ClassNode c = xaero.get(owner); c != null; c = xaero.get(c.superName))
            for (MethodNode m : c.methods) if (m.name.equals(name)) return true;
        return false;
    }

    private boolean requireClass(String owner, String where) {
        if (xaero.containsKey(owner)) return true;
        missing.add("class " + owner + " (" + where + ")");
        return false;
    }

    private void requireMethod(String owner, String name, String desc, String where) {
        if (!requireClass(owner, where)) return;
        if (findMember(owner, name, desc, true)) return;
        missing.add("method " + owner + "." + name + desc + " (" + where + ")");
    }

    private void requireField(String owner, String name, String desc, String where) {
        if (!requireClass(owner, where)) return;
        if (findMember(owner, name, desc, false)) return;
        missing.add("field " + owner + "." + name + ":" + desc + " (" + where + ")");
    }

    /** Looks through superclasses and interfaces; a hierarchy that leaves Xaero's jars is assumed to provide it. */
    private boolean findMember(String owner, String name, String desc, boolean method) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>(List.of(owner));
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            if (current == null || !seen.add(current)) continue;
            ClassNode c = xaero.get(current);
            if (c == null) return !current.equals("java/lang/Object");
            if (method) {
                for (MethodNode m : c.methods) if (m.name.equals(name) && m.desc.equals(desc)) return true;
            } else for (FieldNode f : c.fields) if (f.name.equals(name) && f.desc.equals(desc)) return true;
            queue.add(c.superName);
            queue.addAll(c.interfaces);
        }
        return false;
    }

    private static Object get(AnnotationNode a, String key) {
        if (a.values == null) return null;
        for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(key)) return a.values.get(i + 1);
        return null;
    }

    @SafeVarargs
    private static List<AnnotationNode> annotations(List<AnnotationNode>... lists) {
        List<AnnotationNode> all = new ArrayList<>();
        for (List<AnnotationNode> list : lists) if (list != null) all.addAll(list);
        return all;
    }

    private static boolean has(List<AnnotationNode> a, List<AnnotationNode> b, String simple) {
        return annotations(a, b).stream().anyMatch(n -> n.desc.endsWith("/" + simple + ";"));
    }
}
