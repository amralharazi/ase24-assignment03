import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Fuzzer {
    private static final Pattern HTML_LINE_PATTERN = Pattern.compile("<(\\w+)(?:\\s+(\\w+)=\"([^\"]*)\")*>(.*?)</(\\w+)>");

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java Fuzzer.java \"<command_to_fuzz>\"");
            System.exit(1);
        }
        String commandToFuzz = args[0];
        String workingDirectory = "./";

        if (!Files.exists(Paths.get(workingDirectory, commandToFuzz))) {
            throw new RuntimeException("Could not find command '%s'.".formatted(commandToFuzz));
        }

        String seedInput = "<html a=\"value\">...</html>";

        ProcessBuilder builder = getProcessBuilderForCommand(commandToFuzz, workingDirectory);
        System.out.printf("Command: %s\n", builder.command());

        Matcher matcher = HTML_LINE_PATTERN.matcher(seedInput);

        if (matcher.matches()) {

            String openingTag = matcher.group(1);
            String attributeName = matcher.group(2);
            String attributeValue = matcher.group(3);
            String content = matcher.group(4);
            String closingTag = matcher.group(5);

            runCommand(builder, seedInput, getMutatedInputs(seedInput, List.of(

                                    // The four cases that reveal the issues within the program
                                    input -> insertAngleBracketAoTheBeginningOfSeed(seedInput),
                                    input -> input.replace(openingTag, getRepeatedHtmlPart(openingTag, 16)),
                                    input -> input.replace(content, getRepeatedHtmlPart(content, 64)),
                                    input -> input.replace(attributeValue, getRepeatedHtmlPart(attributeValue, 8)),

                                    input -> input.replace(openingTag, getRepeatedHtmlPart(openingTag, 100)),
                                    input -> input.replace(attributeName, getRepeatedHtmlPart(attributeName, 100)),
                                    input -> input.replace(attributeValue, getRepeatedHtmlPart(attributeValue, 100)),
                                    input -> input.replace(content, getRepeatedHtmlPart(content, 100)),
                                    input -> input.replace(closingTag, getRepeatedHtmlPart(closingTag, 100)),

                                    input -> input.replace(openingTag, reverseHtmlPart(closingTag)),
                                    input -> input.replace(attributeName, reverseHtmlPart(attributeValue)),
                                    input -> input.replace(content, reverseHtmlPart(closingTag)),

                                    input -> insertSeedWithinHtmlPart(seedInput, openingTag),
                                    input -> insertSeedWithinHtmlPart(seedInput, attributeName),
                                    input -> insertSeedWithinHtmlPart(seedInput, attributeValue),
                                    input -> insertSeedWithinHtmlPart(seedInput, content),
                                    input -> insertSeedWithinHtmlPart(seedInput, closingTag)
                            )
                    )
            );

        } else {
            System.out.println("Input seed is an invalid HTML line");
            System.exit(1);
        }
    }

    private static ProcessBuilder getProcessBuilderForCommand(String command, String workingDirectory) {
        ProcessBuilder builder = new ProcessBuilder();
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        if (isWindows) {
            builder.command("cmd.exe", "/c", command);
        } else {
            builder.command("sh", "-c", command);
        }
        builder.directory(new File(workingDirectory));
        builder.redirectErrorStream(true); // redirect stderr to stdout
        return builder;
    }

    private static void runCommand(ProcessBuilder builder, String seedInput, List<String> mutatedInputs) {
        Stream.concat(Stream.of(seedInput), mutatedInputs.stream()).forEach(
                input -> {
                    try {
                        Process process = builder.start();
                        System.out.printf("Input: %s\n", input);

                        OutputStream streamToCommand = process.getOutputStream();
                        streamToCommand.write(input.getBytes());
                        streamToCommand.flush();
                        streamToCommand.close();

                        int exitCode = process.waitFor();
                        System.out.printf("Exit code: %s\n", exitCode);

                        InputStream streamFromCommand = process.getInputStream();
                        String output = readStreamIntoString(streamFromCommand);
                        streamFromCommand.close();
                        System.out.printf("Output: %s\n", output
                                .replaceAll("warning: this program uses gets\\(\\), which is unsafe.", "")
                                .trim());


                        if (exitCode != 0) {
                            System.exit(exitCode);
                        }

                    } catch (Exception e) {
                        System.out.println("Error starting process: " + e.getMessage());
                        System.exit(1);
                    }
                }
        );

        System.exit(0);
    }

    private static String readStreamIntoString(InputStream inputStream) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        return reader.lines()
                .map(line -> line + System.lineSeparator())
                .collect(Collectors.joining());
    }

    private static List<String> getMutatedInputs(String seedInput, Collection<Function<String, String>> mutators) {
        return mutators.stream()
                .map(mutator -> mutator.apply(seedInput))
                .collect(Collectors.toList());
    }

    private static String insertAngleBracketAoTheBeginningOfSeed(String inputSeed){
        return new StringBuilder(inputSeed).insert(0, "<").toString();
    }

    private static String getRepeatedHtmlPart(String part, int breakingThreshold) {
        StringBuilder mutatedPart = new StringBuilder(part);

        while ((mutatedPart.length() <= breakingThreshold)) {
            mutatedPart.append(part);
        }

        return mutatedPart.toString();
    }

    private static String reverseHtmlPart(String part) {
        return new StringBuilder(part).reverse().toString();
    }

    private static String insertSeedWithinHtmlPart(String seedInput, String part) {
        return seedInput.replace(part, seedInput);
    }
}

