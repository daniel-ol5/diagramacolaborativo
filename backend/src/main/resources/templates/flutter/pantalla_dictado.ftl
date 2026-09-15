// Generado por ModelCollab desde el diagrama "${nombreDelDiagrama}".
//
// La pantalla del asistente: se dicta, se REVISA lo entendido, y recien ahi se
// guarda.
//
// El paso de revision no es un adorno. El reconocimiento de voz devuelve lo
// que entendio, no lo que se dijo, y una de las acciones posibles es ELIMINAR.
// Un asistente que ejecuta sin mostrar borra datos por una palabra mal oida, y
// el usuario se entera cuando ya no estan. Mismo criterio que el modal de la
// importacion por foto: se afloja donde hay alguien mirando, y solo ahi.
<#noparse>
import 'package:flutter/material.dart';

import '../api/cliente_api.dart';
import '../datos/cola_local.dart';
import '../nlu/catalogo.dart';
import '../nlu/clasificador.dart';
import '../nlu/extractor.dart';
import '../voz/servicio_de_voz.dart';

class PantallaDictado extends StatefulWidget {
  const PantallaDictado({super.key, required this.titulo});

  final String titulo;

  @override
  State<PantallaDictado> createState() => _PantallaDictadoState();
}

class _PantallaDictadoState extends State<PantallaDictado> {
  final ServicioDeVoz _voz = ServicioDeVoz();
  final ClienteApi _api = ClienteApi();
  final Extractor _extractor = const Extractor();

  Clasificador? _clasificador;
  ColaLocal? _cola;

  String _dictado = '';
  Reconocimiento? _reconocido;
  Map<String, String> _datos = <String, String>{};
  String _estado = 'Preparando...';
  bool _escuchando = false;

  @override
  void initState() {
    super.initState();
    _preparar();
  }

  Future<void> _preparar() async {
    final Catalogo catalogo = await Catalogo.cargar();
    final ColaLocal cola = await ColaLocal.abrir();
    final bool listo = await _voz.iniciar();

    if (!mounted) {
      return;
    }
    setState(() {
      _clasificador = Clasificador(catalogo);
      _cola = cola;
      _estado = listo
          ? 'Manten presionado y dicta una orden.'
          : 'No pude usar el microfono. Revisa los permisos.';
    });
  }

  Future<void> _dictar() async {
    final Clasificador? clasificador = _clasificador;
    if (clasificador == null) {
      return;
    }
    setState(() {
      _escuchando = true;
      _estado = 'Escuchando...';
      _reconocido = null;
      _datos = <String, String>{};
    });

    final String texto = await _voz.escuchar();
    final Reconocimiento? reconocido = clasificador.clasificar(texto);

    if (!mounted) {
      return;
    }
    setState(() {
      _escuchando = false;
      _dictado = texto;
      _reconocido = reconocido;
      _datos = reconocido == null
          ? <String, String>{}
          : _extractor.extraer(reconocido);
      _estado = reconocido == null
          ? 'No entendi esa orden.'
          : 'Revisa antes de confirmar.';
    });
  }

  /// Guarda local SIEMPRE, y despues intenta mandar.
  ///
  /// En ese orden y no al reves: si primero se intentara mandar, una orden
  /// dictada sin conexion se perderia sin dejar rastro.
  Future<void> _confirmar() async {
    final Reconocimiento? reconocido = _reconocido;
    final ColaLocal? cola = _cola;
    if (reconocido == null || cola == null) {
      return;
    }

    await cola.encolar(
      reconocido.intencion.entidad,
      reconocido.intencion.accion,
      _datos,
    );
    final int enviadas = await cola.sincronizar(_api);

    final String mensaje = enviadas > 0
        ? 'Listo, quedo guardado.'
        : 'Guardado en el telefono. Se enviara cuando haya conexion.';
    await _voz.decir(mensaje);

    if (!mounted) {
      return;
    }
    setState(() {
      _estado = mensaje;
      _reconocido = null;
      _datos = <String, String>{};
    });
  }

  @override
  void dispose() {
    _api.cerrar();
    _cola?.cerrar();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final Reconocimiento? reconocido = _reconocido;

    return Scaffold(
      appBar: AppBar(title: Text(widget.titulo)),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Text(_estado, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            if (_dictado.isNotEmpty)
              // Se muestra lo que se OYO, aunque no se haya entendido: es la
              // unica pista que tiene el usuario de por que fallo. "Fractura"
              // en pantalla explica en un segundo lo que un "no entendi" no
              // explica nunca.
              Text('Escuche: "$_dictado"'),
            const SizedBox(height: 12),
            if (reconocido != null) ...<Widget>[
              Text(
                '${reconocido.intencion.accion} sobre ${reconocido.intencion.entidad}',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 8),
              Expanded(
                child: ListView(
                  children: <Widget>[
                    for (final Ranura ranura in reconocido.intencion.ranuras)
                      ListTile(
                        dense: true,
                        title: Text(ranura.nombre),
                        subtitle: Text(_datos[ranura.nombre] ?? '(sin dato)'),
                        // Lo que falta y es obligatorio se marca: confirmar
                        // sin eso deja un registro a medias en el servidor.
                        trailing: ranura.obligatoria &&
                                !_datos.containsKey(ranura.nombre)
                            ? const Icon(Icons.error_outline)
                            : null,
                      ),
                  ],
                ),
              ),
              FilledButton(
                onPressed: _confirmar,
                child: const Text('Confirmar'),
              ),
            ] else
              const Spacer(),
            const SizedBox(height: 12),
            FilledButton.tonalIcon(
              onPressed: _escuchando ? null : _dictar,
              icon: const Icon(Icons.mic),
              label: Text(_escuchando ? 'Escuchando...' : 'Dictar'),
            ),
          ],
        ),
      ),
    );
  }
}
</#noparse>
