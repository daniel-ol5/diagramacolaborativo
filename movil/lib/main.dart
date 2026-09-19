import 'package:flutter/material.dart';

import 'models/diagram_summary.dart';
import 'services/config_service.dart';
import 'ui/capture_screen.dart';
import 'ui/login_screen.dart';
import 'ui/project_list_screen.dart';

void main() {
  runApp(const ModelCollabMovilApp());
}

class ModelCollabMovilApp extends StatelessWidget {
  const ModelCollabMovilApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ModelCollab Móvil',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo)),
      home: const _SplashRouter(),
    );
  }
}

/// Sin sesion guardada, pide login. Con sesion Y un ultimo destino
/// guardado, entra directo a la pantalla de captura -- el caso real es
/// "parado frente a la pizarra", no "elegir proyecto de nuevo cada vez que
/// se abre la app". Con sesion pero sin destino todavia (primera vez),
/// muestra el selector.
class _SplashRouter extends StatefulWidget {
  const _SplashRouter();

  @override
  State<_SplashRouter> createState() => _SplashRouterState();
}

class _SplashRouterState extends State<_SplashRouter> {
  @override
  void initState() {
    super.initState();
    _decidirRuta();
  }

  Future<void> _decidirRuta() async {
    final sesion = await ConfigService.sesionGuardada();
    if (!mounted) return;

    if (sesion == null) {
      Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => const LoginScreen()));
      return;
    }

    final destino = await ConfigService.ultimoDestino();
    if (!mounted) return;

    if (destino == null) {
      Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => ProjectListScreen(sesion: sesion)));
      return;
    }

    Navigator.of(context).pushReplacement(MaterialPageRoute(
      builder: (_) => CaptureScreen(
        sesion: sesion,
        proyectoNombre: destino.proyectoNombre,
        diagrama: DiagramSummary(id: destino.diagramaId, projectId: destino.proyectoId, name: destino.diagramaNombre),
      ),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return const Scaffold(body: Center(child: CircularProgressIndicator()));
  }
}
