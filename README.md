# RoboKnow

<p align="center">
  <strong>🚀 Enterprise-grade RAG + Agent Knowledge Base System</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4.2-brightgreen" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Java-17-orange" alt="Java">
  <img src="https://img.shields.io/badge/Vue-3-42b883" alt="Vue">
  <img src="https://img.shields.io/badge/Elasticsearch-8.10-005571" alt="Elasticsearch">
  <img src="https://img.shields.io/badge/License-MIT-blue" alt="License">
</p>

---

## 📖 Introduction

This is a personal learning project for deeply studying and practicing RAG (Retrieval-Augmented Generation), agentic tool-calling, and enterprise-level system development.

RoboKnow is an enterprise-grade AI knowledge base system that combines Retrieval-Augmented Generation with a ReAct-style agent, hybrid search, and layered conversation memory. It supports multi-tenant architecture, letting users query a knowledge base in natural language and receive AI-generated, document-grounded responses through a persistent, resumable chat session.

### System Architecture

```mermaid
graph TB
    subgraph Frontend["🖥️ Frontend (Vue 3 + TypeScript)"]
        UI[Naive UI Components]
        Pinia[Pinia State Management]
        Router[Vue Router]
    end

    subgraph Backend["⚙️ Backend (Spring Boot 3.4)"]
        Controller[REST Controllers]
        Service[Business Services]
        Agent[ReAct Agent + Tools]
        WebSocket[WebSocket Handler]
        Security[Spring Security + JWT]
    end

    subgraph DataLayer["💾 Data Layer"]
        MySQL[(MySQL 8.0)]
        ES[(Elasticsearch 8.10)]
        Redis[(Redis Cache)]
        MinIO[(MinIO Storage)]
    end

    subgraph MessageQueue["📨 Message Queue"]
        Kafka[Apache Kafka]
    end

    subgraph AI["🤖 AI Services (OpenAI)"]
        Embedding[text-embedding-3-large]
        LLM[gpt-4o-mini]
    end

    Frontend -->|HTTP/WebSocket| Backend
    Controller --> Service
    Service --> MySQL
    Service --> ES
    Service --> Redis
    Service --> MinIO
    Service --> Kafka
    Service --> Embedding
    WebSocket --> Agent
    Agent --> LLM
    Agent --> ES
    Kafka -->|Async Processing| Service
```

The system allows users to:

- Upload and manage various types of documents
- Automatically process, chunk, and index document content
- Query the knowledge base using natural language over a persistent chat session
- Receive AI-generated responses grounded in retrieved document chunks, with citations

## 🛠️ Technology Stack

### Backend Technologies

| Category | Technology |
|----------|------------|
| Framework | Spring Boot 3.4.2 (Java 17) |
| Database | MySQL 8.0 |
| ORM | Spring Data JPA |
| Cache | Redis |
| Search Engine | Elasticsearch 8.10.0 |
| Message Queue | Apache Kafka |
| File Storage | MinIO |
| Document Parsing | Apache Tika |
| Security | Spring Security + JWT |
| AI Integration | OpenAI API (gpt-4o-mini chat + text-embedding-3-large embeddings) |
| Agent | ReAct-style tool-calling agent (hybrid search, metadata filter, summarization tools) |
| Real-time Communication | WebSocket |
| Dependency Management | Maven |
| Reactive Programming | WebFlux |

### Frontend Technologies

| Category | Technology |
|----------|------------|
| Framework | Vue 3 + TypeScript |
| Build Tool | Vite |
| UI Components | Naive UI |
| State Management | Pinia |
| Routing | Vue Router |
| Styling | UnoCSS + SCSS |
| Icons | Iconify |
| Package Manager | pnpm |

## 📁 Project Structure

### Backend Structure

```bash
src/main/java/com/yizhaoqi/roboknow/
├── RoboKnowApplication.java      # Main application entry
├── agent/                        # ReAct agent (state, steps, tools)
│   └── tool/                     # Agent tools: hybrid search, metadata filter, summarization
├── client/                       # External API clients (OpenAI chat/embedding)
├── config/                       # Configuration classes (Security, WebClient, etc.)
├── consumer/                     # Kafka consumers
├── controller/                   # REST API endpoints
├── entity/                       # Search/document DTOs (ES doc, chunk, search req/result)
├── exception/                    # Custom exceptions
├── handler/                      # WebSocket handlers
├── memory/                       # Conversation memory (short/long-term, compression, token budget)
├── model/                        # JPA entities (User, FileUpload, ConversationSession, etc.)
├── repository/                   # Data access layer
├── service/                      # Business logic
└── utils/                        # Utility classes
```

### Frontend Structure

```bash
frontend/
├── packages/           # Reusable modules
├── public/             # Static assets
├── src/
│   ├── assets/         # SVG icons, images
│   ├── components/     # Vue components
│   ├── constants/      # Shared constants
│   ├── enum/           # Enum definitions
│   ├── hooks/          # Composables
│   ├── layouts/        # Page layouts
│   ├── locales/        # i18n resources
│   ├── plugins/        # Vue plugin setup
│   ├── router/         # Route configuration
│   ├── service/        # API integration
│   ├── store/          # Pinia state management
│   ├── styles/         # Global styles
│   ├── theme/          # Theme configuration
│   ├── typings/        # TypeScript type declarations
│   ├── utils/          # Utility functions
│   └── views/          # Page components
└── ...                 # Build configuration files
```

## 🎯 Core Features

### 📚 Knowledge Base Management

RoboKnow provides complete document upload and parsing, supporting chunked file uploads and resumable transfers, with tag-based organization management. Documents can be public or private and associated with organization tags for permission scoping.

#### Document Processing Pipeline

```mermaid
graph LR
    subgraph Upload["📤 Upload Phase"]
        A[User Upload] --> B[Chunked Upload]
        B --> C[MD5 Verification]
        C --> D[MinIO Storage]
    end

    subgraph Parse["📄 Parse Phase"]
        D --> E[Kafka Message]
        E --> F[Apache Tika Parser]
        F --> G[Text Chunking]
        G --> H[Parent-Child Strategy]
    end

    subgraph Vector["🔢 Vectorization Phase"]
        H --> I[OpenAI Embedding API]
        I --> J[Vector Generation]
        J --> K[(Elasticsearch Index)]
    end

    subgraph Meta["💾 Metadata"]
        D --> L[(MySQL Metadata)]
        K --> L
    end

    style Upload fill:#e1f5fe
    style Parse fill:#fff3e0
    style Vector fill:#e8f5e9
    style Meta fill:#fce4ec
```

### 🧠 Agentic RAG

The core of RoboKnow is a ReAct-style agent that decides when to search, filter, and summarize instead of a single fixed retrieval pass:

#### RAG Chat Flow

```mermaid
sequenceDiagram
    participant U as 👤 User
    participant WS as 🔌 WebSocket
    participant AG as 🧭 ReAct Agent
    participant HS as 🔍 Hybrid Search Tool
    participant ES as 📊 Elasticsearch
    participant LLM as 🤖 LLM (gpt-4o-mini)

    U->>WS: Send Question
    WS->>AG: Dispatch turn with session context

    loop Reason-Act steps
        AG->>LLM: Reason about next action
        AG->>HS: Invoke tool (search / filter / summarize)
        HS->>ES: KNN Vector Search + BM25 Text Match
        ES-->>HS: Merged, rescored results
        HS-->>AG: Tool result (chunks / summary)
    end

    AG->>AG: Ground answer in retrieved chunks
    AG->>LLM: Generate final response
    LLM-->>WS: Stream response
    WS-->>U: Real-time typing effect
```

- Semantic chunking of uploaded documents (parent-child strategy)
- OpenAI `text-embedding-3-large` embeddings for each chunk
- Vectors stored in Elasticsearch, combined with BM25 for hybrid recall
- Agent tools (`HybridSearchTool`, `MetadataFilterTool`, `SummarizationTool`) let the model decide what to retrieve and when
- Conversation memory (short-term session context + long-term user facts, with token-budget-aware compression) keeps multi-turn chats grounded without blowing the context window

### 🏢 Enterprise Multi-tenancy

RoboKnow supports multi-tenant architecture through organization tags. Each user can create or join one or more organizations, each with independent knowledge bases and document management, so multiple teams can share the same deployment without data or permission bleed-through.

### 💬 Real-time, Persistent Conversations

The system uses WebSocket for real-time chat, with conversation turns persisted server-side (commit-then-push) so sessions survive reconnects and can be resumed, listed, and switched between via the conversation API.

## 📋 Prerequisites

Before getting started, please ensure the following software is installed:

- Java 17
- Maven 3.8.6 or higher
- Node.js 18.20.0 or higher
- pnpm 8.7.0 or higher
- MySQL 8.0
- Elasticsearch 8.10.0
- MinIO 8.5.12
- Kafka 3.2.1
- Redis 7.0.11
- Docker (optional, for running Redis, MinIO, Elasticsearch, and Kafka services)
- An OpenAI API key (chat completions + embeddings)

## 🏗️ Architecture Design

### Layered Architecture

```mermaid
graph TB
    subgraph Presentation["🎨 Presentation Layer"]
        REST[REST Controllers]
        WS[WebSocket Handlers]
    end

    subgraph Business["⚙️ Business Layer"]
        DocSvc[Document Service]
        SearchSvc[Hybrid Search Service]
        AgentSvc[ReAct Agent Service]
        MemSvc[Memory Manager]
        AuthSvc[User Service]
        UploadSvc[Upload Service]
    end

    subgraph DataAccess["💾 Data Access Layer"]
        FileRepo[File Upload Repository]
        UserRepo[User Repository]
        ConvRepo[Conversation Repository]
        VectorRepo[Document Vector Repository]
    end

    subgraph External["🌐 External Services"]
        ESClient[Elasticsearch Client]
        MinIOClient[MinIO Client]
        KafkaProducer[Kafka Producer]
        OpenAIClient[OpenAI Client]
    end

    Presentation --> Business
    Business --> DataAccess
    Business --> External
    DataAccess --> MySQL[(MySQL)]
    ESClient --> ES[(Elasticsearch)]
    MinIOClient --> MinIO[(MinIO)]
    KafkaProducer --> Kafka[(Kafka)]

    style Presentation fill:#e3f2fd
    style Business fill:#fff3e0
    style DataAccess fill:#e8f5e9
    style External fill:#fce4ec
```

### Key REST Endpoints

| Area | Base Path | Examples |
|------|-----------|----------|
| Auth | `/api/v1/auth` | refresh token |
| Users | `/api/v1/users` | register, login, `me`, org tags, logout |
| Documents | `/api/v1/documents` | delete, accessible list, uploads, download, preview, stream |
| Upload | `/api/v1/upload` | chunked upload, status, merge, supported types |
| Search | `/api/v1/search` | `/hybrid` |
| Chat | `/api/v1/chat` | websocket token issuance |
| Conversation | `/api/v1/users/conversation` | list, sessions, switch, delete |
| Admin | `/api/v1/admin` | users, org tags, knowledge base, system status |
| AI Usage | `/api/v1/ai/usage` | token usage reporting |

## 🚀 Quick Start

### Backend Setup

```bash
# Clone the project
git clone git@github.com:Automan1218/RoboKnow.git

# Navigate to project directory
cd RoboKnow

# Start dependency services (MySQL, Redis, MinIO, Elasticsearch, Kafka)
docker-compose -f docs/docker-compose.yaml up -d

# Set required environment variables (JWT secret, OpenAI key, DB credentials, ...)
# then build and run
mvn spring-boot:run
```

### Frontend Setup

```bash
# Navigate to frontend directory
cd frontend

# Install dependencies
pnpm install

# Start the project
pnpm run dev
```

## 📄 License

This project is for learning purposes only.

## ⭐ Show Your Support

Give a ⭐️ if this project helped you!
