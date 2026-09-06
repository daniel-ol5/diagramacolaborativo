package com.modelcollab.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.collaboration.interceptor.StompChannelInterceptor;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.DefaultContentTypeResolver;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** Transporte de la colaboración en tiempo real: WebSocket nativo con protocolo STOMP. */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompChannelInterceptor stompChannelInterceptor;
    private final ObjectMapper objectMapper;

    public WebSocketConfig(
            StompChannelInterceptor stompChannelInterceptor, ObjectMapper objectMapper) {
        this.stompChannelInterceptor = stompChannelInterceptor;
        this.objectMapper = objectMapper;
    }

    /**
     * Endpoint único {@code /ws}, <b>sin SockJS</b>. SockJS existe para navegadores sin
     * WebSocket, que en 2026 no son un caso real, y a cambio impone una negociación previa por
     * HTTP y rutas {@code /info}, {@code /xhr_streaming}… que habría que abrir en Nginx y en
     * Spring Security. El cliente web usa @stomp/stompjs sobre WebSocket puro y en AWS (H3)
     * Nginx termina el WSS: una sola ruta que proteger en lugar de media docena.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Origen abierto sólo para desarrollo; en H3 se restringe al dominio real.
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Broker en memoria: alcanza para los 20 clientes concurrentes del NFR. §5.3 documenta
        // el reemplazo por un relay Redis si hiciera falta escalar horizontalmente, sin que el
        // protocolo del cliente cambie.
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    /** Acá se engancha el arbitraje de roles: todo frame entrante pasa por el interceptor. */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompChannelInterceptor);
    }

    /**
     * Fuerza el ObjectMapper canónico también en STOMP. Por defecto Spring le arma uno propio al
     * converter, y entonces los mensajes WebSocket se deserializarían con reglas distintas a las
     * de REST y las de JSONB — el contrato de §7 quedaría congelado en el papel y no en los
     * hechos. En particular se pierde {@code FAIL_ON_UNKNOWN_PROPERTIES}, que es el único
     * mecanismo que detecta que alguien se desvió del schema.
     */
    @Override
    public boolean configureMessageConverters(List<MessageConverter> converters) {
        DefaultContentTypeResolver resolver = new DefaultContentTypeResolver();
        resolver.setDefaultMimeType(MimeTypeUtils.APPLICATION_JSON);

        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        converter.setContentTypeResolver(resolver);

        converters.add(converter);
        return false; // false = no agregues los converters por defecto, este es el único
    }
}
