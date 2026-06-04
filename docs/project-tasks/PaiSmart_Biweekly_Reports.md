# PaiSmart Biweekly Development Reports

## Report 1: 10 Apr 2026 - 23 Apr 2026

### Work Completed

During this period, I started Phase 1 of the PaiSmart project and focused on building the foundation of the system. I clarified the project scope as an enterprise-style RAG knowledge-base application, with the main modules covering user authentication, file upload, document processing, hybrid retrieval, and AI chat.

I set up the backend project structure using Spring Boot and Java 17. The codebase was organized into controller, service, repository, model, config, client, consumer, handler, exception, and utility layers. I also configured the Maven dependencies required for Spring Web, Spring Security, JPA, Redis, WebSocket, WebFlux, MinIO, Apache Tika, Kafka, Elasticsearch, JWT, validation, and testing.

The database design was also completed in this stage. I created the core schema for users, uploaded files, chunk records, document vectors, conversations, and organization tags. Even though organization-level access control belongs mainly to Phase 2, I included the required organization fields early so the schema would not need a large redesign later.

For authentication, I implemented the user model, password hashing, JWT utilities, registration API, login API, current-user profile API, logout flow, logout-all flow, refresh-token flow, and Redis-based token cache. I also configured Spring Security, CORS, stateless session handling, and the JWT authentication filter so protected APIs can receive the authenticated user context.

The first part of the file upload module was also implemented. I created file metadata persistence, chunk metadata persistence, file type validation, MinIO configuration, chunk upload API, upload status query API, and resumable upload support.

### Difficulties Encountered

The first challenge was deciding how to divide the project into clear phases. PaiSmart includes many different technologies, so it was easy for the scope to become too large too early. Authentication, file upload, parsing, retrieval, AI chat, and organization permission logic are all connected, but implementing everything at once would make the project difficult to control.

Another difficulty was designing the database schema in a way that supports both Phase 1 and Phase 2. If the first version only supported personal files, then Phase 2 organization permissions would require major schema changes. I had to think ahead and include fields such as organization tags and public/private flags before the permission system was fully implemented.

The JWT and token-cache design also required careful handling. A simple JWT login flow is easy to implement, but logout, logout-all, refresh token, token blacklist, and server-side token validation add more complexity.

For file upload, the main difficulty was supporting resumable upload rather than only simple single-file upload. The system needed to track chunk indexes, chunk MD5 values, uploaded chunks, and merge status correctly.

### Solutions

I solved the scope problem by defining Phase 1 as the core MVP and keeping Phase 2 for organization-level access control. This allowed me to finish authentication, upload, retrieval, and chat first while still leaving room for later permission expansion.

For the schema, I added organization-related fields early, including `org_tags`, `primary_org`, `org_tag`, and `is_public`. This made the database ready for Phase 2 without requiring a full migration later.

For authentication, I separated JWT generation and parsing into utility logic, then used Redis to manage token validity, refresh tokens, and blacklisted tokens. This made the login system more controllable than a purely stateless JWT implementation.

For upload, I split the process into chunk upload, status query, and merge steps. This made the upload process more reliable and allowed the frontend to resume interrupted uploads.

### Next Focus

The next focus is to complete file merge, asynchronous processing, document parsing, vectorization, Elasticsearch indexing, hybrid search, and document management APIs.

---

## Report 2: 24 Apr 2026 - 7 May 2026

### Work Completed

During this period, I completed most of the core Phase 1 backend workflow. I implemented the file merge endpoint, which combines uploaded chunks into a final file, stores the file in MinIO, persists file metadata, and prepares the file for asynchronous processing.

I configured Kafka producer and consumer logic for the file-processing pipeline. After a file is uploaded and merged, the backend creates a file-processing task and the Kafka consumer triggers parsing and vectorization. This prevents the upload API from blocking while the document is being processed.

I implemented document parsing with Apache Tika. The parsing service extracts text from uploaded documents and handles edge cases such as empty content, long text, mixed-language text, and small chunk sizes. I also implemented text chunking so large documents can be split into searchable pieces for embedding and retrieval.

The embedding and vectorization workflow was also added. Parsed chunks are sent to the embedding API, converted into vector representations, and prepared with metadata such as file MD5, user ID, organization tag, and public/private status.

I configured Elasticsearch and created the knowledge-base index mapping. The mapping supports both text search and vector search, while also storing metadata for file ownership and access control. I implemented bulk indexing, index initialization, and deletion by file MD5.

Hybrid search was implemented by combining keyword retrieval and vector retrieval. The results are merged, ranked, and returned as top knowledge chunks through a REST API. This became the retrieval foundation for both the search page and the AI chat flow.

I also implemented document management APIs, including uploaded file listing, accessible file listing, document preview, document download, and document deletion.

For the chat module, I implemented the DeepSeek client, WebSocket configuration, WebSocket handler, chat processing flow, stop-response support, conversation persistence, and conversation history query APIs.

### Difficulties Encountered

The largest difficulty in this period was connecting the full RAG pipeline from upload to search. The file does not become useful immediately after upload; it must be merged, parsed, chunked, embedded, indexed, and then retrieved. Each step depends on the previous step, so debugging failures required checking several components.

Kafka integration also introduced complexity. The producer and consumer must use compatible serialization settings, and the task message must include enough context for parsing and vectorization. If the message lacks user or organization metadata, the later permission filtering logic becomes unreliable.

Another difficulty was designing Elasticsearch indexing for both Phase 1 search and Phase 2 permissions. The index needed to store not only text and vectors, but also file metadata, owner information, organization tag, and public/private state.

The chat module also required multiple moving parts: WebSocket connection handling, AI client calls, message streaming behavior, stop-response logic, and conversation persistence.

### Solutions

I solved the RAG pipeline complexity by separating it into clear stages: upload, merge, Kafka task, parse, chunk, vectorize, index, search, and chat. Each stage has its own service or component, which makes the flow easier to test and debug.

For Kafka, I defined a dedicated file-processing task object that carries file ID, user ID, organization tag, and visibility metadata. This keeps the asynchronous pipeline consistent with the permission model.

For Elasticsearch, I designed the mapping to support both retrieval quality and access control. Text fields support keyword search, vector fields support semantic search, and metadata fields support filtering and deletion.

For chat, I separated the WebSocket handler, chat handler, stop-response service, AI client, and conversation service. This reduced coupling and made it easier to add tests for each part.

### Next Focus

The next focus is to finish the frontend pages for authentication, knowledge-base management, upload progress, chat, conversation history, and then start Phase 2 organization-scope permission work.

---

## Report 3: 8 May 2026 - 15 May 2026

### Work Completed

During this period, I completed the main Phase 1 frontend work and started Phase 2 organization-scope development.

On the frontend side, I built the Vue application layout, authenticated route structure, global menu, page container, route guards, auth store, and API request foundation. I connected login, registration, current-user loading, logout, token storage, and user session management to the backend APIs.

I implemented the knowledge-base frontend page with a document table, upload dialog entry, delete action, preview/download actions, and search dialog entry. I also built the upload queue with chunk upload, progress display, interrupted state, resume upload, merge request, and task synchronization.

The chat frontend page was also implemented. It includes the message list, input box, WebSocket store integration, response rendering, and basic interaction states. I also added the conversation history page for administrator review.

I added Phase 1 regression tests for the main backend areas, including authentication, user management, upload, search, document management, chat, conversation, parsing, JWT behavior, and utility services. A Jacoco report was generated to show the current testing status.

After that, I started Phase 2. I implemented the organization tag entity, repository, default organization initialization, and admin APIs for creating, listing, editing, deleting, and building organization-tag trees. I also implemented user organization assignment APIs so an administrator can assign one or more organization tags to a user.

For normal users, I added APIs to query assigned organization tags, change the primary organization, and load allowed upload organizations. I also started the permission layer, including effective tag lookup, admin checks, owner checks, public document checks, and organization membership checks.

Finally, I extended the upload and frontend organization features by adding organization tag selection, public/private metadata, user tag assignment UI, organization-tag management UI, and personal organization context.

### Difficulties Encountered

The main difficulty was moving from a personal knowledge-base model to an organization-scoped model. In Phase 1, most data access can be based on the owner user ID. In Phase 2, access depends on several rules: the document owner, public visibility, the document organization tag, the user's assigned organization tags, the user's primary organization, and admin privileges.

Another difficulty was keeping the frontend and backend permission model consistent. The frontend should only show upload organization options that the user can actually use, but the backend still needs to enforce the same rules because frontend checks alone are not secure.

Testing also became more complex. Phase 1 tests mostly check whether an API works. Phase 2 tests must check whether a user is correctly allowed or denied based on different organization scenarios.

There was also some integration complexity around upload metadata. The organization tag and public/private flag must be passed from the upload dialog to the chunk upload request, merge request, file metadata table, Kafka task, vectorization flow, and Elasticsearch index.

### Solutions

I handled the permission complexity by introducing dedicated organization and permission services instead of scattering permission checks across controllers. This makes the rules easier to reuse for documents, uploads, search, and future chat retrieval.

I added backend APIs for upload organization choices, user organization details, and primary organization selection. This allows the frontend to display valid options while still leaving final enforcement to the backend.

For upload metadata, I carried organization and visibility fields through the upload flow and prepared them for later indexing and filtering. This keeps document ownership and organization scope consistent across MySQL and Elasticsearch.

For testing, I started separating Phase 1 regression coverage from Phase 2 permission coverage. Phase 1 verifies that the original MVP features still work, while Phase 2 will focus on access-control cases such as owner access, public access, same-organization access, different-organization denial, and admin access.

### Next Focus

The next focus is to finish Phase 2 integration testing, complete frontend organization-management dialogs, verify permission filtering for document lists and search results, and ensure upload metadata stays consistent from frontend to database to Elasticsearch.
