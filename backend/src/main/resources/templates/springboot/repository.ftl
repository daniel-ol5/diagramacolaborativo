package ${paquete}.repository;

import ${paquete}.domain.model.${clase};
<#if importeDelId??>
import ${importeDelId};
</#if>
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositorio JPA de "${nombreOriginal}".
 *
 * <p>NO editar a mano: este archivo se regenera desde el diagrama.
 */
@Repository
public interface ${clase}Repository extends JpaRepository<${clase}, ${tipoDelId}> {
}
