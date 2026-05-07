# LangChain4j RAG Application

This is a Retrieval-Augmented Generation (RAG) application built using Spring Boot and LangChain4j. The purpose of this application is to demonstrate how to ingest a text corpus into a local embedding store and query it using an LLM via Amazon Bedrock, providing a web-based chat interface to interact with the knowledge base.

It also features a scheduled Confluence and GitHub Scraper to automatically fetch pages, download attachments, extract text from images and PDFs using Bedrock, and sync markdown from GitHub repositories to keep the knowledge base up-to-date.

## Prerequisites
- Java 21
- Maven
- AWS Credentials configured or an AWS Bearer Token
- GitHub Token (optional, for GitHub scraping)

## Local Setup
1. **Install Dependencies:**
   Ensure you have Java 21 and Maven installed.
2. **Inject Knowledge Base:**
   Place your knowledge base text file at `src/main/resources/messaging_support_corpus.txt`. The application will parse and embed this corpus on startup.
3. **Configure AWS Bedrock Access:**
   Provide the environment variable `AWS_BEARER_TOKEN_BEDROCK` to authenticate with Amazon Bedrock.
4. **Configure Confluence Access (Optional but recommended for the Scraper):**
   Set the following environment variables or application properties to enable Confluence scraping:
   - `CONFLUENCE_BASE_URL` (e.g. `https://your-domain.atlassian.net/wiki`)
   - `CONFLUENCE_TOKEN` (Your Confluence API Token)
   - `CONFLUENCE_START_PAGE_ID` (e.g. `1262683220`)
5. **Configure GitHub Access (Optional):**
   Provide the environment variable `GITHUB_TOKEN` to enable GitHub repository scraping.

### Why is the GITHUB_TOKEN necessary?
In the context of building a GitHub scraper for your RAG application, the `GITHUB_TOKEN` (Personal Access Token) is primarily needed to handle API Rate Limiting, along with ensuring reliable background execution.

#### 1. Overcoming Strict Rate Limits (The Main Reason)
The GitHub REST API imposes strict rate limits on how many requests you can make:
- **Unauthenticated Requests:** Limited to 60 requests per hour per IP address.
- **Authenticated Requests (with a Token):** Limited to 5,000 requests per hour.

The `GithubMarkdownScraper` will need to make multiple API calls per repository:
- One call to get the repository's file tree.
- One additional call for every single `.md` file it finds to download the raw content.

If a repository has 20 markdown files, and you are scanning 6 repositories, you will easily exceed the 60 requests/hour limit. If the scraper runs without a token, it will crash with a `403 Forbidden (Rate Limit Exceeded)` error before it finishes building the corpus.

## Running the Application
You can run the application using the included Maven wrapper:

```bash
./mvnw spring-boot:run
```

Or on Windows:
```cmd
mvnw.cmd spring-boot:run
```

### Manual Scraper Execution
The application automatically syncs with Confluence and GitHub every Sunday at midnight. You can also trigger all scrapers manually on startup by providing the `--scrape-all` argument. Ensure you have a `.env` file in the root directory with the necessary configuration.

Alternatively, you can run specific scraper tasks by providing one or more of the following arguments:
- `--sync-github`: Syncs markdown files from configured GitHub repositories.
- `--resync-confluence`: Syncs metadata and text content from Confluence.
- `--translate-images`: Translates downloaded images and attachments.
- `--rebuild-corpus`: Rebuilds the combined text corpus file.

Since `./mvnw spring-boot:run` does not load `.env` files out of the box, you can load the variables into your shell environment first (e.g., in bash: `export $(grep -v '^#' .env | xargs)`), or use a tool like `dotenv-cli`.

```bash
export $(grep -v '^#' .env | xargs)
./mvnw spring-boot:run -Dspring-boot.run.arguments="--scrape-all"
# Or for specific tasks:
# ./mvnw spring-boot:run -Dspring-boot.run.arguments="--resync-confluence,--translate-images"
```

## Application Structure
- `src/main/resources/static`: Contains the frontend web interface (`chat.html`, `chat.js`, `style.css`).
- `src/main/resources/messaging_support_corpus.txt`: A text corpus used for the RAG knowledge base.
- `src/main/resources/application.yml`: Application configuration.
- `src/main/java/.../scraper`: Contains the `ConfluenceIncrementalScraper` and `GithubMarkdownScraper` which pull data from Confluence and GitHub and translate assets using Bedrock.

## API Documentation
Once the application is running, you can access the Swagger UI to interact with the API endpoints:
`http://localhost:8080/swagger-ui.html`

## Interacting with the App
A web-based chat interface is available at `http://localhost:8080/chat.html` once the application is started.
