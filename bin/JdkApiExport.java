import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import javax.lang.model.element.*;
import javax.lang.model.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

/**
 * Fast JDK API exporter using javax.lang.model (in-process, no javap overhead).
 *
 * Usage:
 *   java JdkApiExport.java export  <out.csv>    # exports current JDK's API
 *   java JdkApiExport.java diff    <old.csv> <new.csv> <diff.csv>
 */
public class JdkApiExport {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { printUsage(); return; }
        switch (args[0]) {
            case "export" -> doExport(args.length > 1 ? args[1] : "api.csv");
            case "diff"   -> doDiff(args[1], args[2], args[3]);
            default       -> printUsage();
        }
    }

    static void printUsage() {
        System.out.println("""
            Usage:
              java JdkApiExport.java export  <out.csv>
              java JdkApiExport.java diff    <old.csv> <new.csv> <diff.csv>
            """);
    }

    // ==================== export ====================

    static void doExport(String outCsv) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) { System.err.println("No system compiler (need JDK)"); System.exit(1); }
        var fm = compiler.getStandardFileManager(null, null, null);
        var elements = new ElementsLite(fm);
        var modules = listCurrentModules();

        int moduleCount = 0, typeCount = 0, memberCount = 0;
        try (var w = new PrintWriter(new FileWriter(outCsv, java.nio.charset.StandardCharsets.UTF_8))) {
            w.println("Module\tPackage\tTypeKind\tTypeName\tMemberKind\tMemberName\tModifiers\tSignature");
            for (String modName : modules) {
                var me = elements.el.getModuleElement(modName);
                if (me == null) continue;
                moduleCount++;
                for (var pe : elements.getExportedPackages(me)) {
                    for (var te : elements.getTypes(pe)) {
                        typeCount++;
                        var tiRow = String.join("\t",
                            modName, pe.getQualifiedName().toString(),
                            te.getKind().toString(), te.getSimpleName().toString(),
                            "", "", "", "");
                        w.println(tiRow);
                        for (var e : te.getEnclosedElements()) {
                            if (!e.getModifiers().contains(Modifier.PUBLIC)) continue;
                            if (skipElement(e)) continue;
                            memberCount++;
                            var row = String.join("\t",
                                modName, pe.getQualifiedName().toString(),
                                te.getKind().toString(), te.getSimpleName().toString(),
                                kindLabel(e), e.getSimpleName().toString(),
                                modsString(e), sigString(e));
                            w.println(row);
                        }
                    }
                }
            }
        }
        System.out.printf("Exported: %d modules, %d types, %d members → %s%n",
            moduleCount, typeCount, memberCount, outCsv);
    }

    // ==================== diff  ====================

    record ApiRow(String module, String pkg, String typeKind, String typeName,
                  String memberKind, String memberName, String modifiers, String signature) {
        String typeKey()  { return module + "\t" + pkg + "\t" + typeName; }
        String fullKey()  { return typeKey() + "\t" + memberKind + "\t" + memberName + "\t" + signature; }
        boolean isType()  { return memberKind.isEmpty(); }
    }

    static List<ApiRow> loadCsv(String path) throws IOException {
        var rows = new ArrayList<ApiRow>();
        try (var r = new BufferedReader(new FileReader(path, java.nio.charset.StandardCharsets.UTF_8))) {
            r.readLine(); // skip header
            String line;
            while ((line = r.readLine()) != null) {
                var parts = line.split("\t", -1);
                if (parts.length < 8) continue;
                rows.add(new ApiRow(parts[0], parts[1], parts[2], parts[3],
                                    parts[4], parts[5], parts[6], parts[7]));
            }
        }
        return rows;
    }

    static void doDiff(String oldCsv, String newCsv, String diffCsv) throws IOException {
        var oldRows = loadCsv(oldCsv);
        var newRows = loadCsv(newCsv);

        var oldMembers = new HashMap<String, ApiRow>();
        var newMembers = new HashMap<String, ApiRow>();
        var oldTypes   = new HashSet<String>();
        var newTypes   = new HashSet<String>();

        for (var r : oldRows) { if (r.isType()) oldTypes.add(r.typeKey()); else oldMembers.put(r.fullKey(), r); }
        for (var r : newRows) { if (r.isType()) newTypes.add(r.typeKey()); else newMembers.put(r.fullKey(), r); }

        int added = 0, changed = 0, removed = 0;

        try (var w = new PrintWriter(new FileWriter(diffCsv, java.nio.charset.StandardCharsets.UTF_8))) {
            w.println("Status\tModule\tPackage\tTypeKind\tTypeName\tMemberKind\tMemberName\tOldModifiers\tOldSignature\tNewModifiers\tNewSignature");

            for (var r : newRows) {
                if (r.isType()) continue;
                var tk = r.typeKey();
                if (oldTypes.contains(tk) && !newTypes.contains(tk)) continue; // shouldn't happen
                if (!newTypes.contains(tk)) continue;
                if (!oldTypes.contains(tk)) { // new type → all members are new
                    w.println(row("ADDED", r, new ApiRow(r.module, r.pkg, r.typeKind, r.typeName, "", "", "", "")));
                    added++;
                    continue;
                }
                var om = oldMembers.get(r.fullKey());
                if (om == null) { added++; w.println(row("ADDED", r, new ApiRow(r.module, r.pkg, r.typeKind, r.typeName, "", "", "", ""))); }
                else if (!om.modifiers.equals(r.modifiers) || !om.signature.equals(r.signature)) {
                    changed++; w.println(row("CHANGED", r, om));
                }
            }

            for (var r : oldRows) {
                if (r.isType()) continue;
                if (!oldTypes.contains(r.typeKey()) || !newTypes.contains(r.typeKey())) {
                    removed++; w.println(row("REMOVED", new ApiRow("","","","","","","",""), r));
                    continue;
                }
                if (!newMembers.containsKey(r.fullKey())) {
                    removed++; w.println(row("REMOVED", new ApiRow("","","","","","","",""), r));
                }
            }
        }
        System.out.printf("Diff: +%d  ~%d  -%d  → %s%n", added, changed, removed, diffCsv);
    }

    static String row(String status, ApiRow nr, ApiRow or) {
        return String.join("\t", status,
            nr.module, nr.pkg, nr.typeKind, nr.typeName,
            nr.memberKind, nr.memberName,
            or.modifiers, or.signature,
            nr.modifiers, nr.signature);
    }

    // ==================== helpers ====================

    static List<String> listCurrentModules() throws IOException {
        var proc = new ProcessBuilder("java", "--list-modules").redirectErrorStream(true).start();
        try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            return reader.lines()
                .map(l -> l.split("@")[0].trim())
                .filter(s -> s.startsWith("java.") || s.startsWith("jdk."))
                .sorted().toList();
        }
    }

    static String kindLabel(Element e) {
        return switch (e.getKind()) {
            case METHOD          -> "method";
            case CONSTRUCTOR     -> "constructor";
            case FIELD           -> "field";
            case ENUM_CONSTANT   -> "field";
            case RECORD_COMPONENT-> "record_component";
            default              -> e.getKind().toString().toLowerCase();
        };
    }

    static boolean skipElement(Element e) {
        var k = e.getKind();
        return k != ElementKind.METHOD
            && k != ElementKind.CONSTRUCTOR
            && k != ElementKind.FIELD
            && k != ElementKind.ENUM_CONSTANT;
    }

    static String modsString(Element e) {
        return e.getModifiers().stream()
            .map(Modifier::toString).sorted()
            .collect(Collectors.joining(" "));
    }

    static String sigString(Element e) {
        String s = e.toString();
        // compact multi-line to single line
        return s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    // Lightweight Elements wrapper
    static class ElementsLite {
        final Elements el;
        ElementsLite(StandardJavaFileManager fm) {
            // minimal compilation task to get Elements
            var task = ToolProvider.getSystemJavaCompiler().getTask(
                null, fm, null,
                List.of(), null, List.of());
            this.el = task.getElements();
        }

        Set<PackageElement> getExportedPackages(ModuleElement me) {
            var pkgs = new LinkedHashSet<PackageElement>();
            for (var d : me.getDirectives()) {
                if (d.getKind() == ModuleElement.DirectiveKind.EXPORTS) {
                    var pe = ((ModuleElement.ExportsDirective) d).getPackage();
                    if (pe != null) pkgs.add(pe);
                }
            }
            return pkgs;
        }

        List<TypeElement> getTypes(PackageElement pe) {
            return pe.getEnclosedElements().stream()
                .filter(e -> e instanceof TypeElement)
                .filter(e -> e.getModifiers().contains(Modifier.PUBLIC))
                .map(e -> (TypeElement) e)
                .sorted(Comparator.comparing(a -> a.getSimpleName().toString()))
                .toList();
        }
    }
}
