-- CU19: chat grupal por proyecto, con la IA como un integrante mas.
--
-- Vive en la MISMA base que el resto del sistema y no en un almacen aparte: el chat es de un
-- proyecto, se borra con el proyecto (ON DELETE CASCADE) y no justifica sumar un servicio que
-- instalar, respaldar y vigilar.

-- Contador de secuencia por proyecto. Es el mismo principio que diagrams.mutation_version: el
-- siguiente numero se obtiene con UN solo INSERT ... ON CONFLICT DO UPDATE ... RETURNING, que el
-- motor ejecuta de forma atomica, asi que dos mensajes simultaneos nunca reciben el mismo seq.
-- Es una tabla aparte y no un MAX(seq)+1 sobre chat_messages porque ese calculo no es atomico.
CREATE TABLE chat_counters (
    project_id UUID PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    last_seq BIGINT NOT NULL
);

CREATE TABLE chat_messages (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    -- El diagrama que la persona tenia abierto, como contexto. Borrar el diagrama no borra la
    -- conversacion: el mensaje queda, sin referencia.
    diagram_id UUID REFERENCES diagrams(id) ON DELETE SET NULL,
    seq BIGINT NOT NULL,
    author_type VARCHAR(10) NOT NULL CHECK (author_type IN ('USER', 'AI', 'SYSTEM')),
    -- Sin FK a users a proposito: si alguien borra su cuenta, lo que dijo en el chat sigue
    -- siendo parte del hilo de los demas. author_name y author_color guardan como se lo veia.
    author_id UUID,
    author_name VARCHAR(150) NOT NULL,
    author_color VARCHAR(16),
    addressed_to_id UUID,
    addressed_to_name VARCHAR(150),
    reply_to_id UUID REFERENCES chat_messages(id) ON DELETE SET NULL,
    -- 100 y no menos: es el largo maximo que acepta ChatService. Uno menor dejaria pasar la
    -- validacion y fallaria recien en el INSERT, como un error 500.
    client_message_id VARCHAR(100),
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('CHAT', 'ANSWER', 'REVIEW', 'SUMMARY', 'SYSTEM')),
    text TEXT NOT NULL,
    meta JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Orden total del chat de un proyecto. Ademas de garantizar que no se repita un seq, es el
-- indice por el que se pagina el historial (latest, before, after).
CREATE UNIQUE INDEX uq_chat_messages_project_seq ON chat_messages (project_id, seq);

-- Idempotencia: un reintento del cliente con el mismo clientMessageId no puede producir un
-- segundo mensaje, ni siquiera con dos instancias del backend compitiendo. Parcial porque solo
-- tiene sentido para los mensajes que traen identificador de cliente.
CREATE UNIQUE INDEX uq_chat_messages_client_message
    ON chat_messages (project_id, client_message_id)
    WHERE client_message_id IS NOT NULL;
