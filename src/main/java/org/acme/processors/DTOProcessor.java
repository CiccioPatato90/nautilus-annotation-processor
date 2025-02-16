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
    // Global DTO package for all generated DTOs.
    private static final String GLOBAL_DTO_PACKAGE = "org.acme.dto";

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        System.out.println("DTOProcessor init!!!");
        filer = env.getFiler();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // For every class annotated with @GenerateDTO:
        for (Element element : roundEnv.getElementsAnnotatedWith(GenerateDTO.class)) {
            // Only process classes
            if (element.getKind() != ElementKind.CLASS) {
                continue;
            }

            TypeElement classElement = (TypeElement) element;
            String originalPackage = processingEnv.getElementUtils()
                    .getPackageOf(classElement).getQualifiedName().toString();
            // Use the global DTO package regardless of the entity's original package.
            String dtoPackageName = GLOBAL_DTO_PACKAGE;

            String entityName = classElement.getSimpleName().toString();
            String dtoName = entityName + "DTO";

            // Check if the entity's direct superclass is annotated with @GenerateDTO.
            String extendsClause = "";
            TypeMirror superType = classElement.getSuperclass();
            if (superType != null && !superType.toString().equals("java.lang.Object")) {
                Element superElement = processingEnv.getTypeUtils().asElement(superType);
                if (superElement != null && superElement.getAnnotation(GenerateDTO.class) != null) {
                    String baseDto = computeDtoType(superElement);
                    extendsClause = " extends " + baseDto;
                }
            }

            System.out.println("Generating " + dtoName + " in package " + dtoPackageName + extendsClause);

            try {
                // Create the source file in the global DTO package.
                JavaFileObject jfo = filer.createSourceFile(dtoPackageName + "." + dtoName, element);
                try (Writer writer = jfo.openWriter()) {
                    // Write package declaration for the DTO.
                    writer.write("package " + dtoPackageName + ";\n\n");

                    // Import the original entity and Lombok annotations.
                    writer.write("import " + originalPackage + "." + entityName + ";\n\n");
                    writer.write("import lombok.Getter;\n");
                    writer.write("import lombok.Setter;\n\n");

                    // Import required classes for collection conversion.
                    writer.write("import java.util.stream.Collectors;\n");
                    writer.write("import java.util.Set;\n");
                    writer.write("import java.util.HashSet;\n\n");

                    // Write Lombok annotations.
                    writer.write("@Getter\n");
                    writer.write("@Setter\n");

                    // Write class declaration with an extends clause if applicable.
                    writer.write("public class " + dtoName + extendsClause + " {\n\n");

                    // Process fields of the entity to generate corresponding DTO fields.
                    // (Assuming only fields declared in this class are generated.)
                    for (Element enclosed : classElement.getEnclosedElements()) {
                        if (enclosed.getKind() == ElementKind.FIELD) {
                            if (enclosed.getAnnotation(ExcludeFromDTO.class) != null) {
                                continue;
                            }
                            VariableElement field = (VariableElement) enclosed;
                            String fieldName = field.getSimpleName().toString();
                            String fieldType = resolveFieldType(field);
                            writer.write("    private " + fieldType + " " + fieldName + ";\n");
                        }
                    }
                    writer.write("\n");

                    // Generate conversion method from Entity to DTO using getters and setters.
                    writer.write("    public static " + dtoName + " fromEntity(" + entityName + " entity) {\n");
                    writer.write("        " + dtoName + " dto = new " + dtoName + "();\n");
                    for (Element enclosed : classElement.getEnclosedElements()) {
                        if (enclosed.getKind() == ElementKind.FIELD) {
                            if (enclosed.getAnnotation(ExcludeFromDTO.class) != null) {
                                continue;
                            }
                            String fieldName = enclosed.getSimpleName().toString();
                            String capitalized = capitalize(fieldName);
                            String conversion = generateFromEntityConversion(enclosed, fieldName);
                            writer.write("        dto.set" + capitalized + "(" + conversion + ");\n");
                        }
                    }
                    writer.write("        return dto;\n");
                    writer.write("    }\n\n");

                    // Generate static conversion method from DTO to Entity.
                    writer.write("    public static " + entityName + " toEntity(" + dtoName + " dto) {\n");
                    writer.write("        if (dto == null) return null;\n");
                    writer.write("        " + entityName + " entity = new " + entityName + "();\n");
                    for (Element enclosed : classElement.getEnclosedElements()) {
                        if (enclosed.getKind() == ElementKind.FIELD) {
                            if (enclosed.getAnnotation(ExcludeFromDTO.class) != null) {
                                continue;
                            }
                            String fieldName = enclosed.getSimpleName().toString();
                            String capitalized = capitalize(fieldName);
                            String conversion = generateToEntityConversion(enclosed, fieldName);
                            writer.write("        entity.set" + capitalized + "(" + conversion + ");\n");
                        }
                    }
                    writer.write("        return entity;\n");
                    writer.write("    }\n");


                    writer.write("}\n");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    /**
     * Resolves the field type. If the field (or its generic parameter) is annotated with @GenerateDTO,
     * returns the corresponding DTO type in the global package.
     */
    private String resolveFieldType(VariableElement field) {
        String originalType = field.asType().toString();
        // If the field is a declared type, check for annotations.
        if (field.asType() instanceof DeclaredType) {
            DeclaredType dt = (DeclaredType) field.asType();
            List<? extends TypeMirror> typeArgs = dt.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                // Handle collection generic types: e.g., Set<InventoryBox>
                TypeMirror genericType = typeArgs.get(0);
                Element genericElement = processingEnv.getTypeUtils().asElement(genericType);
                if (genericElement != null && genericElement.getAnnotation(GenerateDTO.class) != null) {
                    // Compute DTO type for the generic element in the global package.
                    String dtoGeneric = computeDtoType(genericElement);
                    // Replace the generic parameter in the original type string.
                    String collectionType = originalType.substring(0, originalType.indexOf("<") + 1);
                    return collectionType + dtoGeneric + ">";
                }
            } else {
                // Non-parameterized declared type.
                Element typeElement = processingEnv.getTypeUtils().asElement(field.asType());
                if (typeElement != null && typeElement.getAnnotation(GenerateDTO.class) != null) {
                    return computeDtoType(typeElement);
                }
            }
        }
        return originalType;
    }

    /**
     * Computes the fully qualified DTO type name for a given element.
     * Always uses the global DTO package.
     */
    private String computeDtoType(Element element) {
        return GLOBAL_DTO_PACKAGE + "." + element.getSimpleName().toString() + "DTO";
    }

    /**
     * Generates conversion expression for fromEntity for a given field.
     * If the field's type is annotated with @GenerateDTO, then call the corresponding fromEntity conversion.
     * If it is a collection, map each element.
     */
    private String generateFromEntityConversion(Element field, String fieldName) {
        String expr = "entity.get" + capitalize(fieldName) + "()";
        if (field.asType() instanceof DeclaredType) {
            DeclaredType dt = (DeclaredType) field.asType();
            List<? extends TypeMirror> typeArgs = dt.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                // Collection: assume single generic parameter.
                TypeMirror genericType = typeArgs.get(0);
                Element genericElement = processingEnv.getTypeUtils().asElement(genericType);
                if (genericElement != null && genericElement.getAnnotation(GenerateDTO.class) != null) {
                    String dtoGenericType = computeDtoType(genericElement);
                    String originalFieldType = field.asType().toString();
                    if (originalFieldType.startsWith("java.util.Set")) {
                        expr = "(entity.get" + capitalize(fieldName) + "() == null ? null : entity.get" + capitalize(fieldName) +
                                "().stream().map(e -> " + dtoGenericType + ".fromEntity(e)).collect(Collectors.toSet()))";
                    } else if (originalFieldType.startsWith("java.util.List")) {
                        expr = "(entity.get" + capitalize(fieldName) + "() == null ? null : entity.get" + capitalize(fieldName) +
                                "().stream().map(e -> " + dtoGenericType + ".fromEntity(e)).collect(Collectors.toList()))";
                    } else {
                        // Default to list if unknown collection type.
                        expr = "(entity.get" + capitalize(fieldName) + "() == null ? null : entity.get" + capitalize(fieldName) +
                                "().stream().map(e -> " + dtoGenericType + ".fromEntity(e)).collect(Collectors.toList()))";
                    }
                }
            } else {
                // Non-collection declared type.
                Element typeElement = processingEnv.getTypeUtils().asElement(field.asType());
                if (typeElement != null && typeElement.getAnnotation(GenerateDTO.class) != null) {
                    String dtoType = computeDtoType(typeElement);
                    expr = "(entity.get" + capitalize(fieldName) + "() == null ? null : " + dtoType +
                            ".fromEntity(entity.get" + capitalize(fieldName) + "()))";
                }
            }
        }
        return expr;
    }

    /**
     * Generates conversion expression for toEntity for a given field.
     * In the generated code, we now call the static toEntity method on the DTO class.
     */
    private String generateToEntityConversion(Element field, String fieldName) {
        String expr = "dto.get" + capitalize(fieldName) + "()";
        if (field.asType() instanceof DeclaredType) {
            DeclaredType dt = (DeclaredType) field.asType();
            List<? extends TypeMirror> typeArgs = dt.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                // Collection conversion: map each DTO to entity using the static toEntity conversion.
                TypeMirror genericType = typeArgs.get(0);
                Element genericElement = processingEnv.getTypeUtils().asElement(genericType);
                if (genericElement != null && genericElement.getAnnotation(GenerateDTO.class) != null) {
                    String dtoGenericType = computeDtoType(genericElement);
                    String originalFieldType = field.asType().toString();
                    if (originalFieldType.startsWith("java.util.Set")) {
                        expr = "(dto.get" + capitalize(fieldName) + "() == null ? null : dto.get" + capitalize(fieldName) +
                                "().stream().map(e -> " + dtoGenericType + ".toEntity(e)).collect(Collectors.toSet()))";
                    } else if (originalFieldType.startsWith("java.util.List")) {
                        expr = "(dto.get" + capitalize(fieldName) + "() == null ? null : dto.get" + capitalize(fieldName) +
                                "().stream().map(e -> " + dtoGenericType + ".toEntity(e)).collect(Collectors.toList()))";
                    } else {
                        // Default to Set if the collection type is unknown.
                        expr = "(dto.get" + capitalize(fieldName) + "() == null ? null : dto.get" + capitalize(fieldName) +
                                "().stream().map(e -> " + dtoGenericType + ".toEntity(e)).collect(Collectors.toSet()))";
                    }
                }
            } else {
                // Non-collection: call the static toEntity method on the DTO class.
                Element typeElement = processingEnv.getTypeUtils().asElement(field.asType());
                if (typeElement != null && typeElement.getAnnotation(GenerateDTO.class) != null) {
                    String dtoType = computeDtoType(typeElement);
                    expr = "(dto.get" + capitalize(fieldName) + "() == null ? null : " + dtoType +
                            ".toEntity(dto.get" + capitalize(fieldName) + "()))";
                }
            }
        }
        return expr;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
