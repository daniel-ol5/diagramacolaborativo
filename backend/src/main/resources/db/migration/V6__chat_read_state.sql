-- CU23: historial del chat. Hasta donde leyo cada persona el chat de cada proyecto.
--
-- Una fila por (proyecto, usuario). Sin entidad JPA: se usa con JdbcTemplate, como chat_counters,
-- y last_read_seq solo avanza: el upsert hace GREATEST(existente, nuevo) en una sola sentencia.
-- Se borra con el proyecto y con el usuario (a diferencia de chat_messages.author_id, aca no hay
-- nada que conservar: es estado personal de lectura).
CREATE TABLE chat_read_state (
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    last_read_seq BIGINT NOT NULL DEFAULT 0 CHECK (last_read_seq >= 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);

-- La clave primaria empieza por project_id; este indice es para el borrado en cascada desde
-- users, que busca por user_id.
CREATE INDEX ix_chat_read_state_user ON chat_read_state (user_id);
