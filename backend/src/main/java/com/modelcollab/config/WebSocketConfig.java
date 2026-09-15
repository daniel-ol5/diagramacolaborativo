package com.modelcollab.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.collaboration.interceptor.StompChannelInterceptor;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.DefaultContentTypeResolver;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
        //
        // LATIDOS: sin esto, una sala sin actividad no genera NI UN BYTE, y cualquier
        // intermediario con tiempo de inactividad la corta. No es hipotetico: Nginx corta a los
        // 60 segundos por defecto (proxy_read_timeout) y el balanceador de AWS tambien. En
        // localhost no se nota porque no hay nada en el medio que caduque, asi que el sintoma
        // aparece recien en el despliegue y con la forma mas confusa posible: "la colaboracion
        // se cae sola cada minuto".
        //
        // 10 segundos en cada direccion deja seis veces de margen contra ese limite de 60. Los
        // latidos exigen un planificador propio: enableSimpleBroker sin TaskScheduler los deja
        // apagados sin avisar. Es uno aparte del de @Scheduled a proposito — el barrido de
        // locks corre cada segundo con un solo hilo, y compartirlo haria que un barrido lento
        // atrasara los latidos de toda la sala.
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {10_000L, 10_000L})
                .setTaskScheduler(planificadorDeLatidos());
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

    /**
     * Planificador dedicado a los latidos del broker.
     *
     * <p>Un solo hilo alcanza: mandar un byte por conexion cada 10 segundos con los 20 clientes
     * del NFR no es trabajo. Lo que importa es que <b>no sea el mismo</b> que atiende a
     * {@code @Scheduled}, para que el barrido de locks y los latidos no se pisen.
     */
    @Bean
    public TaskScheduler planificadorDeLatidos() {
        ThreadPoolTaskScheduler planificador = new ThreadPoolTaskScheduler();
        planificador.setPoolSize(1);
        planificador.setThreadNamePrefix("latidos-stomp-");
        planificador.initialize();
        return planificador;
    }
}
