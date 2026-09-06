package com.modelcollab.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita las tareas periódicas. Hoy hay una sola: el barrido de soft-locks vencidos de
 * {@code LockManagerService}, que corre cada segundo y emite {@code LOCK_RELEASED}.
 *
 * <p>Va en su propia clase y no colgado de otra configuración porque olvidarse de
 * {@code @EnableScheduling} es un fallo mudo: los {@code @Scheduled} sencillamente no corren,
 * sin error ni aviso. En este caso el síntoma sería candados que nunca se despintan en las
 * pantallas de los demás, un bug que sólo aparece con dos usuarios y cinco segundos de paciencia.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
