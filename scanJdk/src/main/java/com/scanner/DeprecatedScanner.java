package com.scanner;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

public class DeprecatedScanner {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java -jar scanJdk.jar <scan-path> [output-csv]");
            System.exit(1);
        }

        Path scanRoot = Paths.get(args[0]);
        Path outputCsv = args.length >= 2 ? Paths.get(args[1])
                : Paths.get("deprecated_report.csv");

        try (PrintWriter writer = new PrintWriter(
                Files.newBufferedWriter(outputCsv, StandardCharsets.UTF_8))) {
            writer.println("包名,类名,类@Deprecated有无,方法/属性名,方法/属性@Deprecated有无");

            Files.walkFileTree(scanRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        processFile(file, writer);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        System.out.println("Report written to: " + outputCsv.toAbsolutePath());
    }

    private static void processFile(Path file, PrintWriter writer) {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(file);
        } catch (IOException e) {
            System.err.println("Parse error: " + file + " - " + e.getMessage());
            return;
        }

        String packageName = cu.getPackageDeclaration()
                .map(PackageDeclaration::getNameAsString)
                .orElse("(default)");

        for (TypeDeclaration<?> type : cu.getTypes()) {
            processType(packageName, type, writer);
        }
    }

    private static void processType(String packageName, TypeDeclaration<?> type,
                                    PrintWriter writer) {
        boolean classDeprecated = hasDeprecated(type.getAnnotations());

        if (classDeprecated) {
            return;
        }

        String className = type.getNameAsString();

        for (FieldDeclaration field : type.getFields()) {
            if (!field.isPublic()) {
                continue;
            }
            boolean fieldDeprecated = hasDeprecated(field.getAnnotations());
            for (VariableDeclarator var : field.getVariables()) {
                writer.println(csvLine(packageName, className, "FALSE",
                        var.getNameAsString(), booleanToStr(fieldDeprecated)));
            }
        }

        for (MethodDeclaration method : type.getMethods()) {
            if (!method.isPublic()) {
                continue;
            }
            boolean methodDeprecated = hasDeprecated(method.getAnnotations());
            writer.println(csvLine(packageName, className, "FALSE",
                    method.getNameAsString(), booleanToStr(methodDeprecated)));
        }
    }

    private static boolean hasDeprecated(NodeList<AnnotationExpr> annotations) {
        return annotations.stream()
                .anyMatch(a -> a.getNameAsString().equals("Deprecated"));
    }

    private static String booleanToStr(boolean b) {
        return b ? "TRUE" : "FALSE";
    }

    private static String csvLine(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            String v = values[i];
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }
}
