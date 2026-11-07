/**
 * Parte un texto en tramos para resaltar las coincidencias de la busqueda
 * con <mark>, SIN HTML crudo: cada tramo se dibuja como texto (React lo
 * escapa). Sin distinguir mayusculas, como el servidor.
 *
 * Si pasar a minusculas cambia el largo del texto (algun caracter Unicode
 * raro), no se resalta: mejor nada que marcar el tramo equivocado.
 *
 * Ejemplos:
 *   partirPorCoincidencia("Atributos y atrib", "atrib")
 *     -> [{t:"Atrib",m:true}, {t:"utos y ",m:false}, {t:"atrib",m:true}]
 *   partirPorCoincidencia("hola", "xy")  -> [{t:"hola",m:false}]
 *   partirPorCoincidencia("hola", "")    -> [{t:"hola",m:false}]
 */
export interface Tramo {
  t: string;
  m: boolean;
}

export function partirPorCoincidencia(texto: string, buscado: string | null | undefined): Tramo[] {
  const q = (buscado ?? "").trim().toLowerCase();
  const bajo = texto.toLowerCase();
  if (!q || bajo.length !== texto.length) return [{ t: texto, m: false }];
  const salida: Tramo[] = [];
  let desde = 0;
  for (let i = bajo.indexOf(q); i !== -1; i = bajo.indexOf(q, desde)) {
    if (i > desde) salida.push({ t: texto.slice(desde, i), m: false });
    salida.push({ t: texto.slice(i, i + q.length), m: true });
    desde = i + q.length;
  }
  if (desde < texto.length) salida.push({ t: texto.slice(desde), m: false });
  return salida.length > 0 ? salida : [{ t: texto, m: false }];
}
