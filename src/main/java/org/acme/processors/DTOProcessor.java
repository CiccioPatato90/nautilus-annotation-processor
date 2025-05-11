package org.acme.processors;

import com.google.auto.service.AutoService;
import org.acme.annotations.ExcludeFromDTO;
import org.acme.annotations.GenerateDTO;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import java.io.Writer;
import java.io.IOException;
import java.util.List;
import java.util.Set;

@AutoService(Processor.class)
@SupportedAnnotationTypes("org.acme.annotations.GenerateDTO")
@SupportedSourceVersion(SourceVersion.RELEASE_11)
public class DTOProcessor extends AbstractProcessor {

    private Filer filer;
    private static final String GLOBAL_DTO_PACKAGE = "org.acme.dto";

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        filer = env.getFiler();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(GenerateDTO.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                continue;
            }

            TypeElement classElement = (TypeElement) element;
            String originalPackage = processingEnv.getElementUtils()
                    .getPackageOf(classElement).getQualifiedName().toString();
            String dtoPackageName = GLOBAL_DTO_PACKAGE;

            String entityName = classElement.getSimpleName().toString();
            String dtoName = entityName + "DTO";

            String extendsClause = "";
            TypeMirror superType = classElement.getSuperclass();
            if (superType != null && !superType.toString().equals("java.lang.Object")) {
                Element superElement = processingEnv.getTypeUtils().asElement(superType);
                if (superElement != null && superElement.getAnnotation(GenerateDTO.class) != null) {
                    String baseDto = computeDtoType(superElement);
                    extendsClause = " extends " + baseDto;
                }
            }

            try {
                JavaFileObject jfo = filer.createSourceFile(dtoPackageName + "." + dtoName, element);
                try (Writer writer = jfo.openWriter()) {
                    writer.write("package " + dtoPackageName + ";\n\n");
                    writer.write("import " + originalPackage + "." + entityName + ";\n");
                    writer.write("import lombok.Getter;\nimport lombok.Setter;\n");
                    writer.write("import java.util.stream.Collectors;\n");
                    writer.write("import java.util.Set;\nimport java.util.HashSet;\nimport java.util.List;\n\n");

                    writer.write("@Getter\n@Setter\n");
                    writer.write("public class " + dtoName + extendsClause + " {\n\n");

                    for (Element enclosed : classElement.getEnclosedElements()) {
                        if (enclosed.getKind() == ElementKind.FIELD && enclosed.getAnnotation(ExcludeFromDTO.class) == null) {
                            VariableElement field = (VariableElement) enclosed;
                            String fieldName = field.getSimpleName().toString();
                            String fieldType = resolveFieldType(field);
                            writer.write("    private " + fieldType + " " + fieldName + ";\n");
                        }
                    }
                    writer.write("\n");

                    writer.write("    public static " + dtoName + " fromEntity(" + entityName + " entity) {\n");
                    writer.write("        " + dtoName + " dto = new " + dtoName + "();\n");
                    for (Element enclosed : classElement.getEnclosedElements()) {
                        if (enclosed.getKind() == ElementKind.FIELD && enclosed.getAnnotation(ExcludeFromDTO.class) == null) {
                            String fieldName = enclosed.getSimpleName().toString();
                            String capitalized = capitalize(fieldName);
                            String conversion = generateFromEntityConversion(enclosed, fieldName);
                            writer.write("        dto.set" + capitalized + "(" + conversion + ");\n");
                        }
                    }
                    writer.write("        return dto;\n    }\n\n");

                    writer.write("    public static " + entityName + " toEntity(" + dtoName + " dto) {\n");
                    writer.write("        if (dto == null) return null;\n");
                    writer.write("        " + entityName + " entity = new " + entityName + "();\n");
                    for (Element enclosed : classElement.getEnclosedElements()) {
                        if (enclosed.getKind() == ElementKind.FIELD && enclosed.getAnnotation(ExcludeFromDTO.class) == null) {
                            String fieldName = enclosed.getSimpleName().toString();
                            String capitalized = capitalize(fieldName);
                            String conversion = generateToEntityConversion(enclosed, fieldName);
                            writer.write("        entity.set" + capitalized + "(" + conversion + ");\n");
                        }
                    }
                    writer.write("        return entity;\n    }\n");

                    writer.write("}\n");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    private String resolveFieldType(VariableElement field) {
        String originalType = field.asType().toString();
        if (field.asType() instanceof DeclaredType) {
            DeclaredType dt = (DeclaredType) field.asType();
            List<? extends TypeMirror> typeArgs = dt.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                TypeMirror genericType = typeArgs.get(0);
                Element genericElement = processingEnv.getTypeUtils().asElement(genericType);
                if (genericElement != null && genericElement.getAnnotation(GenerateDTO.class) != null) {
                    String dtoGeneric = computeDtoType(genericElement);
                    String collectionType = originalType.substring(0, originalType.indexOf("<") + 1);
                    return collectionType + dtoGeneric + ">";
                }
            } else {
                Element typeElement = processingEnv.getTypeUtils().asElement(field.asType());
                if (typeElement != null && typeElement.getAnnotation(GenerateDTO.class) != null) {
                    return computeDtoType(typeElement);
                }
            }
        }
        return originalType;
    }

    private String computeDtoType(Element element) {
        return GLOBAL_DTO_PACKAGE + "." + element.getSimpleName().toString() + "DTO";
    }

    private String generateFromEntityConversion(Element field, String fieldName) {
        String expr = "entity.get" + capitalize(fieldName) + "()";
        String type = field.asType().toString();

        if (field.asType() instanceof DeclaredType) {
            DeclaredType dt = (DeclaredType) field.asType();
            List<? extends TypeMirror> typeArgs = dt.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                TypeMirror genericType = typeArgs.get(0);
                Element genericElement = processingEnv.getTypeUtils().asElement(genericType);
                if (genericElement != null && genericElement.getAnnotation(GenerateDTO.class) != null) {
                    String dtoGenericType = computeDtoType(genericElement);
                    if (type.startsWith("java.util.Set")) {
                        return "(" + expr + " == null ? null : " + expr +
                                ".stream().map(e -> " + dtoGenericType + ".fromEntity(e)).collect(Collectors.toSet()))";
                    } else {
                        return "(" + expr + " == null ? null : " + expr +
                                ".stream().map(e -> " + dtoGenericType + ".fromEntity(e)).collect(Collectors.toList()))";
                    }
                }
            } else {
                Element typeElement = processingEnv.getTypeUtils().asElement(field.asType());
                if (typeElement != null && typeElement.getAnnotation(GenerateDTO.class) != null) {
                    String dtoType = computeDtoType(typeElement);
                    return "(" + expr + " == null ? null : " + dtoType + ".fromEntity(" + expr + "))";
                }
            }
        }
        return expr;
    }

    private String generateToEntityConversion(Element field, String fieldName) {
        String expr = "dto.get" + capitalize(fieldName) + "()";
        String type = field.asType().toString();

        if (field.asType() instanceof DeclaredType) {
            DeclaredType dt = (DeclaredType) field.asType();
            List<? extends TypeMirror> typeArgs = dt.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                TypeMirror genericType = typeArgs.get(0);
                Element genericElement = processingEnv.getTypeUtils().asElement(genericType);
                if (genericElement != null && genericElement.getAnnotation(GenerateDTO.class) != null) {
                    String dtoGenericType = computeDtoType(genericElement);
                    if (type.startsWith("java.util.Set")) {
                        return "(" + expr + " == null ? null : " + expr +
                                ".stream().map(e -> " + dtoGenericType + ".toEntity(e)).collect(Collectors.toSet()))";
                    } else {
                        return "(" + expr + " == null ? null : " + expr +
                                ".stream().map(e -> " + dtoGenericType + ".toEntity(e)).collect(Collectors.toList()))";
                    }
                }
            } else {
                Element typeElement = processingEnv.getTypeUtils().asElement(field.asType());
                if (typeElement != null && typeElement.getAnnotation(GenerateDTO.class) != null) {
                    String dtoType = computeDtoType(typeElement);
                    return "(" + expr + " == null ? null : " + dtoType + ".toEntity(" + expr + "))";
                }
            }
        }
        return expr;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
