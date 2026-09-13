package ${paquete}.service;

import ${paquete}.service.dto.${clase}RequestDTO;
import ${paquete}.service.dto.${clase}ResponseDTO;
import java.util.List;
<#list importesServicio as importe>
import ${importe};
</#list>

/**
 * Operaciones de negocio de ${clase} ("${nombreOriginal}" en el diagrama).
 *
 * <p>NO editar a mano: este archivo se regenera desde el diagrama.
 */
public interface ${clase}Service {

    List<${clase}ResponseDTO> listar();

    ${clase}ResponseDTO buscarPorId(${tipoDelIdEfectivo} id);

    ${clase}ResponseDTO crear(${clase}RequestDTO dto);

    ${clase}ResponseDTO actualizar(${tipoDelIdEfectivo} id, ${clase}RequestDTO dto);

    void eliminar(${tipoDelIdEfectivo} id);
<#list metodos as m>

    /** Metodo "${m.nombreOriginal}" definido en el diagrama UML. */
    ${m.retorno} ${m.nombre}(${m.firmaDeParametros});
</#list>
}
