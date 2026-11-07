-- CU21: Configuracion del comportamiento de la IA por proyecto.
--
-- Cada proyecto define cuando interviene la IA, su nivel pedagogico, el proveedor
-- (nube o servidor local) y la frecuencia de resumenes.
-- Modificar exige rol OWNER; consultar esta abierto a cualquier miembro del proyecto.

CREATE TABLE assistant_settings (
    project_id UUID PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    mode VARCHAR(30) NOT NULL DEFAULT 'ON_MENTION'
        CHECK (mode IN ('OFF', 'ON_MENTION', 'ALWAYS', 'MENTOR_PROACTIVE')),
    level VARCHAR(30) NOT NULL DEFAULT 'INTERMEDIATE'
        CHECK (level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    provider VARCHAR(30) NOT NULL DEFAULT 'CLOUD'
        CHECK (provider IN ('CLOUD', 'LOCAL')),
    local_endpoint VARCHAR(500),
    max_tokens INT DEFAULT 400,
    summary_frequency INT DEFAULT 30,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_by_id UUID REFERENCES users(id) ON DELETE SET NULL
);

