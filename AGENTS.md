# AI Agents and Prompt Engineering Guidelines

This document outlines the standards for developing AI Agents within this project, focusing on Clean Code, testability, and effective Prompt Engineering.

## Core Principles

### 1. Clean Code (Robert C. Martin)
- **Small Methods**: Each method should do one thing and do it well.
- **Descriptive Names**: Use names that reveal intent. Avoid abbreviations.
- **Single Responsibility Principle (SRP)**: Each class should have one reason to change.
- **No Fully Qualified Names**: Use Java imports instead of long qualified names in the code (e.g., `import java.util.Scanner;` instead of `java.util.Scanner`).

### 2. Boilerplate Reduction
- **Lombok**: Use `@Slf4j` for logging, `@RequiredArgsConstructor` for constructor injection, and `@Data` or `@Value` for DTOs.
- **Static Imports**: Use static imports for common test matchers or utility methods to improve readability.

### 3. Testability
- **Unit Tests**: Implement fast-executing unit tests.
- **Hamcrest**: Use Hamcrest matchers (`assertThat`, `is`, `equalTo`, `containsString`) for expressive assertions.
- **Minimal Mocking**: Prefer testing logic in isolation. If a component is hard to test, it might need refactoring to follow SRP.

### 4. Prompt Engineering
- **Persona Definition**: Clearly define the role of the agent in the system prompt.
- **Contextual Guidance**: Provide the agent with clear instructions on how to use the RAG corpus and tools.
- **Output Structuring**: Use structured formats (like JSON) where appropriate for programmatic consumption.

## Formatting
- Always follow the `.editorconfig` settings defined in the project root.
- Ensure proper indentation and spacing as per the IDE defaults.

## Agent Architecture
Agents are implemented using the `langchain4j` library. The core logic is defined in the `Assistant` interface, which uses AI Services to bind LLM calls with RAG augmentation.

### Assistant Interface
The `Assistant` interface serves as the primary entry point for user interactions. It is annotated with `@SystemMessage` to define the persona and grounding rules.

### Retrieval Augmentation
The `RagConfiguration` class defines how context is retrieved from the embedding store and injected into the prompts. It includes logic for:
- **Filtering by Score**: Ensuring only relevant documents are used.
- **Context Length Limiting**: Preventing exceeding the LLM's context window.
- **Metadata Injection**: Including URLs and titles for citations.
