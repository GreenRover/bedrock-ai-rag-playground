# LangChain4j RAG Application

A high-performance Retrieval-Augmented Generation (RAG) application for enterprise messaging support.

## 🚀 Key Features
- **Hybrid Search**: Combines PGVector semantic search with PostgreSQL Full-Text search.
- **Multimodal**: Automatically extracts text from diagrams and PDFs using Amazon Bedrock.
- **Multi-Source**: Scrapes data from Confluence, GitHub, and Bitbucket.
- **Self-Healing Knowledge**: Automated translation of documentation into English for a unified vector space.
- **Live Diagnostics**: AI Agent tools to query live Solace broker status.

## 🛠 Tech Stack
- **Backend**: Java 21, Spring Boot 3.4
- **AI**: LangChain4j, Amazon Bedrock (Nova Pro, Titan Embeddings)
- **Database**: PostgreSQL with `pgvector`
- **Processing**: Apache Tika (MIME detection), JSoup (HTML parsing), Flexmark (Markdown)

## 📦 Modules
- `scraper`: Logic for fetching data from external VCS/Wikis.
- `service`: Core RAG logic (Ingestion, Translation, Retrieval).
- `controller`: REST API endpoints.
- `config`: Wiring of Bedrock clients and AI components.

## Local Setup
1. **Install Dependencies:**
   Ensure you have Java 21 and Maven installed.
2. **Configure AWS Bedrock Access:**
   Provide the environment variable `AWS_BEARER_TOKEN_BEDROCK` to authenticate with Amazon Bedrock.
3. **Configure Confluence Access (Optional but recommended for the Scraper):**
   Set the following environment variables or application properties to enable Confluence scraping:
   - `CONFLUENCE_BASE_URL` (e.g. `https://your-domain.atlassian.net/wiki`)
   - `CONFLUENCE_TOKEN` (Your Confluence API Token)
   - `CONFLUENCE_START_PAGE_ID` (e.g. `1262683220`)
4. **Configure GitHub Access (Optional):**
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
- `--sync-bitbucket`: Syncs markdown files from configured Bitbucket repositories.
- `--sync-confluence`: Syncs metadata and text content from Confluence.
- `--translate-images`: Extract text from all images and PDFs.
- `--rebuild-rag`: Rebuilds the rag / PostgreSQL db.
- `--erase-export-dir`: Erases the local export directory before downloading everything again.

Since `./mvnw spring-boot:run` does not load `.env` files out of the box, you can load the variables into your shell environment first (e.g., in bash: `export $(grep -v '^#' .env | xargs)`), or use a tool like `dotenv-cli`.

```bash
export $(grep -v '^#' .env | xargs)
./mvnw spring-boot:run -Dspring-boot.run.arguments="--scrape-all"
# Or for specific tasks:
# ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sync-confluence,--sync-github,--translate-images,--rebuild-rag"
```

## API Documentation
Once the application is running, you can access the Swagger UI to interact with the API endpoints:
`http://localhost:8080/swagger-ui.html`

## Interacting with the App
A web-based chat interface is available at `http://localhost:8080/chat.html` once the application is started.

## 📖 Architecture
Detailed architecture can be found in [doc/ARCHITECTURE.md](doc/ARCHITECTURE.md)
