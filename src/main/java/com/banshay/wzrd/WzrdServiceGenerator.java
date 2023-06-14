package com.banshay.wzrd;

import com.google.auto.service.AutoService;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;

@SupportedAnnotationTypes("com.banshay.wzrd.WzrdEnabled")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@AutoService(Processor.class)
public class WzrdServiceGenerator extends AbstractProcessor {

  private final String valueMemberTemplate = """
          private final Value %sValue;
      """;

  /**
   * @param 1 - filename
   * @param 2 - path
   */
  private final String initializationTemplate =
      """
                var %1$sSource = Source.newBuilder("ruby", new java.io.File("%2$s")).build();
                %1$sValue = context.eval(%1$sSource);
          """;

  /**
   * @param 1 - filename
   * @param 2 - arguments with type
   * @param 3 - returnType
   * @param 4 - arguments with no type
   */
  private final String methodTemplate =
      """
              public reactor.core.publisher.Mono<%3$s> %1$s(%2$s) {
                var executed = %1sValue.execute(%4$s);
                if(executed.isMetaObject()){
                  return reactor.core.publisher.Mono.just(executed.as(%3$s.class));
                }
                return reactor.core.publisher.Mono.empty();
              }
          """;

  /**
   * @param 1 - value definitions
   * @param 2 - source -> value initialization
   * @param 3 - methods
   */
  private final String template =
      """
          package com.banshay.wzrd;

          import org.graalvm.polyglot.*;

          @org.springframework.stereotype.Service
          public class WzrdService {

            private final Context context;
            %1$s

            public WzrdService() throws java.io.IOException {
              this.context = Context.newBuilder().allowAllAccess(true).build();
              %2$s
            }

            %3$s

          }

          """;

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    for (TypeElement annotation : annotations) {
      try {
        var service =
            processingEnv.getFiler().createSourceFile("com.banshay.wzrd.WzrdService", null);

        Filer filer = processingEnv.getFiler();
        var messager = processingEnv.getMessager();

        var genForPath = filer.createSourceFile("PathFor");
        var writer = genForPath.openWriter();
        var sourcePath = genForPath.toUri();
        writer.close();
        genForPath.delete();

        messager.printMessage(Kind.NOTE, sourcePath.getPath());

        var path = Path.of(sourcePath);
        var target = path.getParent().getParent().getParent().getParent();

        messager.printMessage(Kind.NOTE, target.toString());

        var pathMatcher = FileSystems.getDefault().getPathMatcher("glob:**.wzrd");

        var functions =
            Files.walk(target)
                .filter(pathMatcher::matches)
                .map(
                    file -> {
                      var returnType = "Object";
                      try {
                        returnType =
                            Objects.requireNonNullElseGet(
                                    Files.readAllLines(file), List::<String>of)
                                .stream()
                                .filter(line -> line.startsWith("#returns "))
                                .map(line -> line.split("#returns ")[1])
                                .findFirst()
                                .orElse("Object");
                      } catch (IOException ignored) {
                      }
                      var filename = file.getFileName().toString().split(".wzrd")[0];
                      return new WzrdRule(
                          uncapitalize(filename), file.toAbsolutePath().toString(), returnType);
                    })
                .peek(
                    rule ->
                        messager.printMessage(
                            Kind.NOTE, "%s: %s".formatted(rule.filename(), rule.returnType())))
                .toList();

        var serviceWriter = service.openWriter();
        serviceWriter.write(
            template.formatted(
                functions.stream()
                    .map(rule -> valueMemberTemplate.formatted(rule.filename()))
                    .collect(Collectors.joining(System.lineSeparator())),
                functions.stream()
                    .map(
                        rule ->
                            initializationTemplate.formatted(
                                rule.filename(), rule.path().replace("\\", "\\\\")))
                    .collect(Collectors.joining(System.lineSeparator())),
                functions.stream()
                    .map(
                        rule -> {
                          return methodTemplate.formatted(
                              rule.filename(), "Integer input", rule.returnType(), "input");
                        })
                    .collect(Collectors.joining(System.lineSeparator()))));
        serviceWriter.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return true;
  }

  private String uncapitalize(String str) {
    int strLen = Objects.requireNonNullElse(str, "").length();
    if (strLen == 0) {
      return str;
    } else {
      int firstCodepoint = str.codePointAt(0);
      int newCodePoint = Character.toLowerCase(firstCodepoint);
      if (firstCodepoint == newCodePoint) {
        return str;
      } else {
        int[] newCodePoints = new int[strLen];
        int outOffset = 0;
        newCodePoints[outOffset++] = newCodePoint;

        int codepoint;
        for (int inOffset = Character.charCount(firstCodepoint);
            inOffset < strLen;
            inOffset += Character.charCount(codepoint)) {
          codepoint = str.codePointAt(inOffset);
          newCodePoints[outOffset++] = codepoint;
        }

        return new String(newCodePoints, 0, outOffset);
      }
    }
  }

  private record WzrdRule(String filename, String path, String returnType) {}
}
