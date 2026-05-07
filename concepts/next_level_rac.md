# Concept: Tier 3 Enterprise RAG Architecture (OpenShift Edition)

## 1. Executive Summary
Moving to Tier 3 means graduating from an `InMemoryEmbeddingStore` to a persistent, enterprise-grade retrieval pipeline. This resolves data volatility on startup, fixes exact-match keyword failures (e.g., error codes, IPs), and drastically reduces LLM hallucinations by aggressively filtering context before generation.

**Tier 3 introduces three new architectural steps:**
1. **Persistent Vector Database:** Storing embeddings persistently.
2. **Hybrid Search:** Combining Semantic Search (Dense Vectors) with Keyword Search (BM25 / Full-Text Search).
3. **Cross-Encoder Re-ranking:** Using an AI model to logically score the exact relevance of chunks.

---

## 2. Software & Tool Stack (OpenShift Compatible)

Since OpenShift is the target environment and PostgreSQL is already available, here is the recommended tooling stack.

### A. The Database (Vector + Keyword Storage)

**Primary Recommendation: PostgreSQL (Existing)**
Since you already have Postgres in your OpenShift environment, this is the path of least resistance and lowest operational overhead.
* **Vector Search:** Install the **`pgvector`** extension. It integrates perfectly with LangChain4j (`langchain4j-pgvector` dependency) and adds `<->` operator support for fast nearest-neighbor semantic search.
* **Keyword Search:** Use **PostgreSQL Native Full-Text Search (FTS)** (`to_tsvector` and `to_tsquery`). This handles the exact-match keyword searching natively.
* **OpenShift Compatibility:** 100% compatible. Enterprise Postgres operators for OpenShift (like CrunchyData PGO or Zalando Postgres Operator) support `pgvector` out of the box.

**Alternative Recommendation: Qdrant**
If you find that Postgres struggles with scaling vector searches or if you want built-in native Hybrid Search without writing complex SQL joins.
* **Why Qdrant?** It is an open-source, Rust-based vector database specifically designed for AI. It natively supports combining Dense (Semantic) and Sparse (Keyword/BM25) vectors in a single query.
* **OpenShift Compatibility:** Excellent. Qdrant provides an official Helm chart and Kubernetes Operator that deploys easily onto OpenShift. LangChain4j has a dedicated `langchain4j-qdrant` module.

### B. The Re-ranking Engine

**Primary Recommendation: Amazon Bedrock (Cohere Rerank)**
* **Why?** You are already authenticating to AWS Bedrock for the Amazon Nova Pro model and Titan Embeddings.
* **OpenShift Footprint:** Zero. This is purely an API call out to AWS, saving your OpenShift compute resources (CPU/RAM).
* **Tool:** Use the Cohere Rerank v3 model via the Bedrock API.

**Alternative Recommendation: Hugging Face TEI (Hosted on OpenShift)**
If corporate policy requires the re-ranking data to stay on-premise within OpenShift.
* **Tool:** **Text Embeddings Inference (TEI)** by Hugging Face.
* **Why?** It is a blazing-fast, Dockerized toolkit for deploying embeddings and re-ranker models. You can pull an open-source re-ranker (like `BAAI/bge-reranker-v2-m3`) and run it as a Pod in OpenShift.
* **OpenShift Compatibility:** It is distributed as a standard container image. It runs on standard CPUs, though it performs much better if your OpenShift worker nodes have GPUs attached.

---

## 3. Data Flow Diagram

1. **Ingestion (Scheduled Scraper):**
   `Confluence Page` -> `Markdown-Aware Splitter` -> `Bedrock Titan Embedding` -> **`PostgreSQL (pgvector + FTS text columns)`**
2. **Retrieval (User Chat):**
   `User Query` -> **`Custom Hybrid Retriever`** *(Executes Vector query via pgvector AND Keyword query via Postgres FTS)* -> `Top 50 Chunks`
3. **Refinement:**
   `Top 50 Chunks` -> **`Bedrock Cohere Reranker`** -> `Top 5 Chunks`
4. **Generation:**
   `Top 5 Chunks` + `User Query` -> `Bedrock Nova Pro` -> `Final Markdown Answer`

---

## 4. Cost and Benefit Analysis

### Costs (Implementation & Run)
* **Infrastructure Cost:** Very Low. You are reusing the existing PostgreSQL database. OpenShift compute costs remain stable unless you choose to host a local re-ranker via TEI.
* **AI API Costs:** Low. Adding Cohere Rerank via Bedrock costs roughly $2.00 per 1,000 searches.
* **Development Effort:** Medium-High. You will need to write a custom `ContentRetriever` in Spring Boot that executes a combined SQL query (Vector + Full Text Search) against Postgres, and then apply Reciprocal Rank Fusion (RRF) in Java to merge the scores.

### Benefits (ROI)
* **Zero-Downtime Startups:** App boots instantly; data is persistent in Postgres.
* **High-Fidelity IT Support:** Fixes the biggest flaw of standard RAG. Exact matches for logs, IPs, hostnames, and error codes will now succeed via Postgres FTS.
* **Elimination of Hallucinations:** Re-ranking filters out "distractor" chunks before the LLM ever sees them, ensuring the LLM only answers from highly concentrated, strictly relevant facts.

---

## 5. Implementation Roadmap (Postgres Route)

### Phase 1: Persistence & Migration
1. Ensure the `pgvector` extension is enabled on your existing OpenShift Postgres database (`CREATE EXTENSION vector;`).
2. Update `pom.xml` with `langchain4j-pgvector`.
3. Refactor `CorpusIngestor.java` to push embeddings to Postgres instead of the InMemory store. Define a database schema that includes a `vector` column and a `tsvector` column for text search.

### Phase 2: Hybrid Retrieval (The Tricky Part)
1. LangChain4j's default PgVector store only does vector search. You will need to create a custom `ContentRetriever` Bean.
2. In this Bean, use `JdbcTemplate` to execute a hybrid SQL query. Example concept:
   ```sql
   WITH semantic_search AS (
       SELECT id, text, metadata, embedding <-> ? AS vector_score FROM documents ORDER BY vector_score LIMIT 20
   ),
   keyword_search AS (
       SELECT id, text, metadata, ts_rank_cd(text_tsv, plainto_tsquery(?)) AS keyword_score FROM documents WHERE text_tsv @@ plainto_tsquery(?) ORDER BY keyword_score DESC LIMIT 20
   )
   -- Merge results and calculate RRF (Reciprocal Rank Fusion) in Java or via SQL