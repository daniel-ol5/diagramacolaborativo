-- ModelCollab :: esquema inicial (§13 del plan arquitectónico Fase 0).
-- gen_random_uuid() vive en pgcrypto en PG < 13 y en el core desde PG 13;
-- la extensión se crea igual para no depender de la versión del servidor.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Usuarios de la plataforma
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. Proyectos
CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    description TEXT,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. Membresías y Roles de Colaboradores
CREATE TABLE project_members (
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    PRIMARY KEY (project_id, user_id)
);

-- 4. Diagramas (Estado canónico estructurado en JSONB)
CREATE TABLE diagrams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL,
    schema_version VARCHAR(20) NOT NULL DEFAULT '1.0.0',
    mutation_version BIGINT NOT NULL DEFAULT 1,
    current_state JSONB NOT NULL DEFAULT '{"schemaVersion": "1.0.0", "mutationVersion": 1, "classes": [], "relationships": []}'::jsonb,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_diagrams_project ON diagrams(project_id);
CREATE INDEX idx_diagrams_state_gin ON diagrams USING gin (current_state);

-- 5. Log de Operaciones Colaborativas (Auditoría, Historial e Idempotencia)
CREATE TABLE diagram_operations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    operation_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    client_mutation_id VARCHAR(100),
    sequence_num BIGINT NOT NULL,
    server_timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_diagram_ops_seq ON diagram_operations(diagram_id, sequence_num ASC);
CREATE INDEX idx_diagram_ops_client_mut ON diagram_operations(client_mutation_id);
