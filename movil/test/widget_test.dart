import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:movil/main.dart';

void main() {
  testWidgets('sin sesión guardada, arranca en la pantalla de login', (WidgetTester tester) async {
    // Sin esto, SharedPreferences.getInstance() nunca resuelve en un test
    // (no hay canal de plataforma real detrás) y el splash queda girando
    // para siempre -- no es que la app este mal, es que el test necesita
    // su propio "disco" en memoria.
    SharedPreferences.setMockInitialValues({});
    await tester.pumpWidget(const ModelCollabMovilApp());
    await tester.pumpAndSettle();
    expect(find.text('Entrar'), findsOneWidget);
  });
}
