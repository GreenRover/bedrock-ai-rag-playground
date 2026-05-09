# Architecture Document for RAG Application

## Overview

The application is a high-performance Retrieval-Augmented Generation (RAG) system designed to assist Java developers with enterprise messaging topics.
It automates the ingestion of documentation from diverse sources (Confluence, GitHub, Bitbucket), processes multimodal content (images, PDFs), and
provides a chat interface grounded in a hybrid search knowledge base.

## Scope and Context

### Business Context

The system operates as a 3rd-level support specialist for technical queries.

- Users: Java developers need actionable solutions for messaging infrastructure (Solace).
- Documentation Sources: Confluence (Wiki pages), GitHub and Bitbucket (Markdown files in repositories).
- Infrastructure: Solace brokers provide live environment data for real-time diagnostics via AI tools.

### Technical Context

- *Framework*: Spring Boot 3.4 and LangChain4j.
- *AI Services*: Amazon Bedrock (Nova for chat/translation, Titan for embeddings, and Rerank for scoring).
- *Storage*: PostgreSQL with the pgvector extension for storing text segments and high-dimensional embeddings.
- *Data Formats*: Supports Markdown conversion from HTML and automated extraction from images, Draw.io diagrams, and PDFs.

## Solution Strategy

- *Hybrid Retrieval*: To maximize accuracy, the system combines semantic vector search (cosine distance) with keyword-based full-text search (ts_rank_cd) in a single PostgreSQL query.
- *Multimodal Ingestion*: Instead of ignoring diagrams, the system uses Bedrock vision models to generate Markdown descriptions of technical images and UI screenshots, which are then embedded.
- *Language Unification*: All non-English documentation is automatically detected and translated into English before ingestion to ensure a consistent vector space and optimize retrieval performance.
- *Incremental Synchronization*: The Confluence scraper tracks page versions to perform efficient incremental updates rather than full re-scrapes.
- *Hierarchical Chunking*: A custom MarkdownDocumentSplitter preserves document structure by tracking header levels (H1, H2, H3), injecting the parent context into the metadata of each chunk for better LLM grounding.
- *Agentic Tooling*: The AI assistant can transition from static knowledge retrieval to live status checks by invoking tools to list brokers or analyze real-time dataflows.

## Building Blocks

![Data Flow Diagram](data-flow-diagram.png)

### Level 1: Whitebox Overall System

The system is decomposed into four primary architectural layers:

- *API & UI Layer*: Handles user interactions through the ChatController and a browser-based chat client.
- *AI Orchestration*: Managed by the Assistant interface, which defines the persona and connects the LLM to the retrieval augmentor and environment tools.
- *Data Acquisition (Scrapers)*: A suite of scrapers (ConfluenceIncrementalScraper, GithubMarkdownScraper, BitbucketMarkdownScraper) that export content to a standardized local storage.
- *Knowledge Pipeline*: The DocumentBuilderService and DocumentIngestor transform raw files into searchable vector segments.

### Level 2: Core Components

#### Retrieval Sub-system

| Component                     | Responsibility                                                                        |
|:------------------------------|:--------------------------------------------------------------------------------------|
| **PostgresHybridRetriever**   | Executes combined vector and text search queries.                                     |
| **BedrockAmazonScoringModel** | Re-ranks the top retrieved results using a cross-encoder model for maximum relevance. |
| **QueryTransformer**          | Translates the user's query into English to match the internal corpus language.       |

#### Ingestion Sub-system

| Component                   | Responsibility                                                                                              |
|:----------------------------|:------------------------------------------------------------------------------------------------------------|
| DocumentTranslationService  | Translates content to English using Bedrock Nova-Lite.                                                      |
| BedrockMediaTranslation     | Extracts technical descriptions from images and PDFs.                                                       |
| ConfluenceToMarkdownService | Converts Confluence storage format (HTML) into clean Markdown while handling user mentions and attachments. |

#### Live Environment Integration


| Component           | Responsibility                                                                    |
|:--------------------|:----------------------------------------------------------------------------------|
| SolaceBrokerTools   | Provides the AI agent with Java methods to list brokers and test topic dataflows. |
| SolaceBackendFacade | Standardizes REST communication with the Solace Backend of tms-ssp.               |