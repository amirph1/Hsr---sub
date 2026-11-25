import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  runApp(const MaterialApp(
    debugShowCheckedModeBanner: false,
    home: TranslatorSetupScreen(),
  ));
}

class TranslatorSetupScreen extends StatefulWidget {
  const TranslatorSetupScreen({super.key});

  @override
  State<TranslatorSetupScreen> createState() => _TranslatorSetupScreenState();
}

class _TranslatorSetupScreenState extends State<TranslatorSetupScreen> {
  static const platform = MethodChannel('hsr_translator');

  double topPercent = 0.72;
  double heightPercent = 0.20;
  double widthPercent = 0.76;
  double fontSize = 16.0;
  bool isRunning = false;

  final TextEditingController _apiKeyController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      topPercent = prefs.getDouble('topPercent') ?? 0.72;
      heightPercent = prefs.getDouble('heightPercent') ?? 0.20;
      widthPercent = prefs.getDouble('widthPercent') ?? 0.76;
      fontSize = prefs.getDouble('fontSize') ?? 16.0;
      _apiKeyController.text = prefs.getString('geminiApiKey') ?? '';
    });
  }

  Future<void> _saveSettings() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setDouble('topPercent', topPercent);
    await prefs.setDouble('heightPercent', heightPercent);
    await prefs.setDouble('widthPercent', widthPercent);
    await prefs.setDouble('fontSize', fontSize);
    await prefs.setString('geminiApiKey', _apiKeyController.text.trim());
  }

  Future<void> _startTranslator() async {
    await _saveSettings();
    final res = await platform.invokeMethod('startAutoTranslator', {
      'topPercent': topPercent,
      'heightPercent': heightPercent,
      'widthPercent': widthPercent,
      'fontSize': fontSize,
      'geminiApiKey': _apiKeyController.text.trim(),
    });
    if (res == 'STARTED') {
      setState(() => isRunning = true);
    }
  }

  Future<void> _stopTranslator() async {
    await platform.invokeMethod('stopAutoTranslator');
    setState(() => isRunning = false);
  }

  @override
  Widget build(BuildContext context) {
    return Directionality(
      textDirection: TextDirection.rtl,
      child: Scaffold(
        backgroundColor: const Color(0xFF0E111A),
        appBar: AppBar(
          title: const Text('تنظیمات مترجم خودکار HSR', style: TextStyle(fontSize: 17)),
          backgroundColor: const Color(0xFF161B29),
          foregroundColor: Colors.white,
          elevation: 0,
        ),
        body: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const Text(
              'پیش‌نمایش محل زیرنویس در حالت افقی بازی:',
              style: TextStyle(color: Colors.white70, fontSize: 14),
            ),
            const SizedBox(height: 10),
            // پیش‌نمایش صفحه افقی بازی
            AspectRatio(
              aspectRatio: 20 / 9,
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final boxW = constraints.maxWidth * widthPercent;
                  final boxH = constraints.maxHeight * heightPercent;
                  final boxTop = constraints.maxHeight * topPercent;
                  final boxLeft = (constraints.maxWidth - boxW) / 2;

                  return Container(
                    decoration: BoxDecoration(
                      color: const Color(0xFF1C2233),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: Colors.white24, width: 1.5),
                    ),
                    child: Stack(
                      children: [
                        const Center(
                          child: Text(
                            'صفحه بازی (Honkai: Star Rail)',
                            style: TextStyle(color: Colors.white24, fontSize: 13),
                          ),
                        ),
                        Positioned(
                          top: boxTop.clamp(0.0, constraints.maxHeight - boxH),
                          left: boxLeft,
                          width: boxW,
                          height: boxH,
                          child: Container(
                            alignment: Alignment.center,
                            padding: const EdgeInsets.symmetric(horizontal: 8),
                            decoration: BoxDecoration(
                              color: Colors.black.withOpacity(0.75),
                              borderRadius: BorderRadius.circular(8),
                              border: Border.all(color: Colors.cyanAccent, width: 1.5),
                            ),
                            child: Text(
                              'نمونه زیرنویس فارسی روان در این کادر نمایش داده می‌شود',
                              textAlign: TextAlign.center,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: Colors.white,
                                fontSize: fontSize * 0.65,
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  );
                },
              ),
            ),
            const SizedBox(height: 18),
            _buildSlider(
              label: 'موقعیت عمودی (فاصله از بالا): ${(topPercent * 100).toInt()}%',
              value: topPercent,
              min: 0.40,
              max: 0.88,
              onChanged: (v) => setState(() => topPercent = v),
            ),
            _buildSlider(
              label: 'ارتفاع کادر ترجمه: ${(heightPercent * 100).toInt()}%',
              value: heightPercent,
              min: 0.10,
              max: 0.35,
              onChanged: (v) => setState(() => heightPercent = v),
            ),
            _buildSlider(
              label: 'عرض کادر ترجمه: ${(widthPercent * 100).toInt()}%',
              value: widthPercent,
              min: 0.45,
              max: 0.95,
              onChanged: (v) => setState(() => widthPercent = v),
            ),
            _buildSlider(
              label: 'اندازه فونت داخل بازی: ${fontSize.toInt()}',
              value: fontSize,
              min: 12.0,
              max: 24.0,
              onChanged: (v) => setState(() => fontSize = v),
            ),
            const Divider(color: Colors.white12, height: 28),
            const Text(
              'کلید API رایگان Gemini (اختیاری - برای ترجمه کاملاً محاوره‌ای و داستانی):',
              style: TextStyle(color: Colors.white70, fontSize: 13),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _apiKeyController,
              textDirection: TextDirection.ltr,
              style: const TextStyle(color: Colors.white, fontSize: 13),
              decoration: InputDecoration(
                hintText: 'خالی بماند = ترجمه خودکار بدون نیاز به کلید',
                hintStyle: const TextStyle(color: Colors.white38, fontSize: 12),
                filled: true,
                fillColor: const Color(0xFF161B29),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(10),
                  borderSide: BorderSide.none,
                ),
              ),
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF2563EB),
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 15),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              onPressed: _startTranslator,
              icon: const Icon(Icons.play_arrow_rounded),
              label: const Text(
                'فعال‌سازی مترجم خودکار و ورود به بازی',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
              ),
            ),
            const SizedBox(height: 10),
            if (isRunning)
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  foregroundColor: Colors.redAccent,
                  side: const BorderSide(color: Colors.redAccent),
                  padding: const EdgeInsets.symmetric(vertical: 13),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                ),
                onPressed: _stopTranslator,
                icon: const Icon(Icons.stop_rounded),
                label: const Text('توقف مترجم'),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildSlider({
    required String label,
    required double value,
    required double min,
    required double max,
    required ValueChanged<double> onChanged,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(color: Colors.white, fontSize: 13)),
        Slider(
          value: value,
          min: min,
          max: max,
          activeColor: Colors.cyanAccent,
          inactiveColor: Colors.white24,
          onChanged: onChanged,
        ),
      ],
    );
  }
}
