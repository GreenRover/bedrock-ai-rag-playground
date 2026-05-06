# LangChain4j RAG Application

This is a Retrieval-Augmented Generation (RAG) application built using Spring Boot and LangChain4j. The purpose of this application is to demonstrate how to ingest a text corpus into a local embedding store and query it using an LLM via Amazon Bedrock, providing a web-based chat interface to interact with the knowledge base.

## Prerequisites
- Java 21
- Maven
- AWS Credentials configured or an AWS Bearer Token

## Local Setup
1. **Install Dependencies:**
   Ensure you have Java 21 and Maven installed.
2. **Inject Knowledge Base:**
   Place your knowledge base text file at `src/main/resources/messaging_support_corpus.txt`. The application will parse and embed this corpus on startup.
3. **Configure AWS Bedrock Access:**
   Provide the environment variable `AWS_BEARER_TOKEN_BEDROCK` to authenticate with Amazon Bedrock.

## Running the Application
You can run the application using the included Maven wrapper:

```bash
./mvnw spring-boot:run
```

Or on Windows:
```cmd
mvnw.cmd spring-boot:run
```

## Application Structure
- `src/main/resources/static`: Contains the frontend web interface (`chat.html`, `chat.js`, `style.css`).
- `src/main/resources/messaging_support_corpus.txt`: A text corpus used for the RAG knowledge base.
- `src/main/resources/application.yml`: Application configuration.

## API Documentation
Once the application is running, you can access the Swagger UI to interact with the API endpoints:
`http://localhost:8080/swagger-ui.html`

## Interacting with the App
A web-based chat interface is available at `http://localhost:8080/chat.html` once the application is started.
