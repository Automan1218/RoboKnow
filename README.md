# RoboKnow

<p align="center">
  <strong>🚀 Enterprise-grade RAG Intelligent Knowledge Base System</strong>
</p>

<p align="center">
  <a href="./README.md">English</a> | <a href="./README_CN.md">简体中文</a>
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

This is my **personal learning project**, aimed at deeply studying and practicing RAG (Retrieval-Augmented Generation) technology, microservices architecture, and enterprise-level system development.

RoboKnow is an enterprise-grade AI knowledge base management system that leverages Retrieval-Augmented Generation (RAG) technology to provide intelligent document processing and retrieval capabilities.

The core technology stack includes ElasticSearch, Kafka, WebSocket, Spring Security, Docker, MySQL, and Redis.

Its goal is to help enterprises and individuals manage and utilize information in knowledge bases more efficiently. It supports multi-tenant architecture, allows users to query the knowledge base using natural language, and receive AI-generated responses based on their own documents.

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

    subgraph AI["🤖 AI Services"]
        Embedding[Doubao Embedding API]
        LLM[DeepSeek / Ollama LLM]
    end

    Frontend -->|HTTP/WebSocket| Backend
    Controller --> Service
    Service --> MySQL
    Service --> ES
    Service --> Redis
    Service --> MinIO
    Service --> Kafka
    Service --> Embedding
    WebSocket --> LLM
    Kafka -->|Async Processing| Service
```

The system allows users to:

- Upload and manage various types of documents
- Automatically process and index document content
- Query the knowledge base using natural language
- Receive AI-generated responses based on their own documents

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
| AI Integration | DeepSeek API / Local Ollama + Doubao Embedding |
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
src/main/java/com/yizhaoqi/smartpai/
├── SmartPaiApplication.java      # Main application entry
├── client/                       # External API clients
├── config/                       # Configuration classes
├── consumer/                     # Kafka consumers
├── controller/                   # REST API endpoints
├── entity/                       # Data entities
├── exception/                    # Custom exceptions
├── handler/                      # WebSocket handlers
├── model/                        # Domain models
├── repository/                   # Data access layer
├── service/                      # Business logic
└── utils/                        # Utility classes
```

### Frontend Structure

```bash
frontend/
├── packages/           # Reusable modules
├── public/             # Static assets
├── src/                # Main application code
│   ├── assets/         # SVG icons, images
│   ├── components/     # Vue components
│   ├── layouts/        # Page layouts
│   ├── router/         # Route configuration
│   ├── service/        # API integration
│   ├── store/          # State management
│   ├── views/          # Page components
│   └── ...            # Other utilities and configs
└── ...               # Build configuration files
```

## 🎯 Core Features

### 📚 Knowledge Base Management

RoboKnow provides complete document upload and parsing functionality, supporting chunked file uploads and resumable transfers, with tag-based organization management. Documents can be public or private and can be associated with specific organization tags for better permission classification.

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
        H --> I[Doubao Embedding API]
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

### 🧠 AI-Driven RAG Implementation

The core of RoboKnow is the RAG implementation:

#### RAG Chat Flow

```mermaid
sequenceDiagram
    participant U as 👤 User
    participant WS as 🔌 WebSocket
    participant HS as 🔍 Hybrid Search
    participant ES as 📊 Elasticsearch
    participant LLM as 🤖 LLM (DeepSeek)

    U->>WS: Send Question
    WS->>HS: Query with User Context
    
    par Vector Search
        HS->>ES: KNN Vector Search (30x recall)
    and Text Search
        HS->>ES: BM25 Text Match
    end
    
    ES-->>HS: Merged Results
    HS->>HS: Rescore & Re-rank
    HS-->>WS: Top-K Relevant Chunks
    
    WS->>WS: Inject Context into Prompt
    WS->>LLM: Send Augmented Prompt
    LLM-->>WS: Stream Response
    WS-->>U: Real-time Typing Effect
```

- Semantic chunking of uploaded documents
- Calling Doubao Embedding model to generate high-dimensional vectors for each text chunk
- Storing vectors in ElasticSearch to support semantic search and keyword search
- Retrieving relevant documents based on user queries
- Providing complete context to LLM to generate more accurate, document-based responses

### 🏢 Enterprise Multi-tenancy

RoboKnow supports multi-tenant architecture through organization tags. Each user can create or join one or more organizations, and each organization can have independent knowledge bases and document management.

#### Security & Access Control Architecture

```mermaid
graph TB
    subgraph Auth["🔐 Authentication Layer"]
        JWT[JWT Dual-Token]
        AT[Access Token]
        RT[Refresh Token]
        BL[Token Blacklist]
        JWT --> AT
        JWT --> RT
        AT --> BL
    end

    subgraph RBAC["👥 Authorization Layer"]
        Admin[Admin Role]
        User[User Role]
        API[API Permissions]
        Admin --> API
        User --> API
    end

    subgraph MultiTenant["🏢 Multi-Tenant Isolation"]
        OrgTag[Organization Tags]
        Private[Private Documents]
        OrgDocs[Organization Documents]
        Public[Public Documents]
        OrgTag --> Private
        OrgTag --> OrgDocs
        OrgTag --> Public
    end

    subgraph Filter["🛡️ Security Filters"]
        JWTFilter[JWT Auth Filter]
        OrgFilter[OrgTag Auth Filter]
        JWTFilter --> OrgFilter
    end

    Auth --> Filter
    RBAC --> Filter
    Filter --> MultiTenant

    style Auth fill:#ffebee
    style RBAC fill:#e3f2fd
    style MultiTenant fill:#e8f5e9
    style Filter fill:#fff3e0
```

This allows enterprises to manage knowledge bases for multiple teams or departments within the same system without worrying about data confusion or permission issues.

### 💬 Real-time Communication

The system uses WebSocket technology to provide real-time interaction between users and the AI system, supporting responsive chat interfaces for knowledge retrieval and AI interaction.

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

## 🏗️ Architecture Design

RoboKnow's architecture features a modern, cloud-native application with clear separation of concerns, scalable components, and integration with AI technology. The modular design allows for future expansion and replacement of individual components as technology evolves, especially in the rapidly changing field of AI integration.

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
        ChatSvc[Chat Handler]
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
        EmbeddingClient[Embedding Client]
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

### Controller Layer

Handles HTTP requests, validates input, manages request/response formatting, and delegates business logic to the service layer. Controllers are organized by domain functionality. Following RESTful design principles, with integrated performance monitoring and logging for tracking API usage and troubleshooting.

```java
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    @Autowired
    private DocumentService documentService;
    
    @DeleteMapping("/{fileMd5}")
    public ResponseEntity<?> deleteDocument(
            @PathVariable String fileMd5,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        // Parameter validation and delegation to service
        documentService.deleteDocument(fileMd5);
        // Response handling
    }
}
```

### Service Layer

Primarily handles application business logic, with transaction awareness and the ability to handle operations across multiple data sources.

```java
@Service
public class DocumentService {
    @Autowired
    private FileUploadRepository fileUploadRepository;
    
    @Autowired
    private MinioClient minioClient;
    
    @Autowired
    private ElasticsearchService elasticsearchService;
    
    @Transactional
    public void deleteDocument(String fileMd5) {
        // Business logic for document deletion
        // Coordinating multiple repositories and systems
    }
}
```

### Data Access Layer

Uses Spring Data JPA for database operations, providing CRUD operations for MySQL.

```java
@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {
    Optional<FileUpload> findByFileMd5(String fileMd5);
    
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<FileUpload> findAccessibleFilesWithTags(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
}
```

### Entity Layer

Consists of JPA entities mapped to database tables and DTOs (Data Transfer Objects) for API requests and responses.

```java
@Entity
public class FileUpload {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String fileMd5;
    private String fileName;
    private String userId;
    private boolean isPublic;
    private String orgTag;
    // Other fields and methods
}
```

## 🚀 Quick Start

### Backend Setup

```bash
# Clone the project
git clone https://github.com/your-username/roboknow.git

# Navigate to backend directory
cd roboknow

# Start dependency services (Docker Compose)
docker-compose up -d

# Build and run
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
