import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const channel = MethodChannel('wtt/setup');

void main() => runApp(const WordTapApp());

class WordTapApp extends StatelessWidget {
  const WordTapApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Word Tap Translation',
      theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.teal),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  bool loaded = false;
  bool acc = false;
  bool batt = false;
  bool skipped = false;
  bool forced = false;
  bool autoAsked = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) refresh();
  }

  Future<void> refresh() async {
    final a = await channel.invokeMethod<bool>('isAccessibilityOn') ?? false;
    final b = await channel.invokeMethod<bool>('isBatteryOk') ?? false;
    if (!mounted) return;
    setState(() {
      acc = a;
      batt = b;
      loaded = true;
    });
    if (!b && !autoAsked) {
      autoAsked = true;
      channel.invokeMethod('requestBattery');
    }
  }

  Widget step(int n, String title, String body, bool? done, String method) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            CircleAvatar(
              backgroundColor: done == true ? Colors.green : Colors.grey.shade300,
              foregroundColor: done == true ? Colors.white : Colors.black87,
              child: done == true ? const Icon(Icons.check) : Text('$n'),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: const TextStyle(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 4),
                  Text(body),
                ],
              ),
            ),
            const SizedBox(width: 8),
            FilledButton(
              onPressed: () => channel.invokeMethod(method),
              child: const Text('Open'),
            ),
          ],
        ),
      ),
    );
  }

  Widget setupScreen() {
    final ready = acc && batt;
    return Scaffold(
      appBar: AppBar(title: const Text('Setup')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          step(1, 'Turn on accessibility',
              'Find Word Tap Translation in the list and switch it on.', acc, 'openAccessibility'),
          step(2, 'If the switch is blocked',
              'Tap the menu at the top right, choose Allow restricted settings, then repeat step 1.',
              null, 'openAppInfo'),
          step(3, 'Battery: no restrictions',
              'Choose Allow so the service keeps running.', batt, 'requestBattery'),
          step(4, 'Auto-start',
              'Allow the app to start automatically. Skip if your phone has no such option.',
              null, 'openAutoStart'),
          const SizedBox(height: 8),
          FilledButton(
            onPressed: ready
                ? () => setState(() {
                      forced = false;
                      skipped = true;
                    })
                : null,
            child: const Text('Done'),
          ),
          TextButton(
            onPressed: () => setState(() {
              forced = false;
              skipped = true;
            }),
            child: const Text('Skip for now'),
          ),
        ],
      ),
    );
  }

  Widget homeScreen() {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Word Tap Translation'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            tooltip: 'Setup',
            onPressed: () => setState(() => forced = true),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(
            acc ? 'Accessibility: On' : 'Accessibility: Off. Open Setup to turn it on.',
            style: TextStyle(
              fontSize: 16,
              color: acc ? Colors.green.shade700 : Colors.red,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            batt ? 'Battery: Unrestricted' : 'Battery: Restricted. Open Setup to fix it.',
            style: TextStyle(
              fontSize: 16,
              color: batt ? Colors.green.shade700 : Colors.red,
            ),
          ),
          const SizedBox(height: 24),
          const Text('How to use', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          const Text('1. Long-press to select a word in any app: the meaning, then Bangla, appear.'),
          const SizedBox(height: 6),
          const Text('2. Triple-tap a message: the sentence is highlighted. Tap a word in it to see its meaning, then Bangla.'),
          const SizedBox(height: 6),
          const Text('3. Long-press the highlighted sentence to translate all of it. Tap Speak to hear, Close to dismiss.'),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (!loaded) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    final showSetup = forced || (!(acc && batt) && !skipped);
    return showSetup ? setupScreen() : homeScreen();
  }
}
