Hotbar To Chest - Fabric - Minecraft Java 1.21.11

عملکرد:
- Chest، Double Chest و Ender Chest را پشتیبانی می‌کند.
- تا وقتی GUI چست باز است، Hotbar را دائماً بررسی می‌کند.
- هر Stack را کامل با QUICK_MOVE (Shift Click) منتقل می‌کند.
- آیتم‌هایی که بعداً هنگام AFK وارد Hotbar شوند نیز منتقل می‌شوند.
- پس از بستن چست فوراً متوقف می‌شود.
- بین هر بررسی/انتقال حدود 250ms فاصله است.

نیاز برای اجرا:
- Minecraft Java 1.21.11
- Fabric Loader 0.18.4 یا جدیدتر سازگار با 1.21.11
- Fabric API برای 1.21.11

برای ساخت JAR بدون نصب برنامه:
1) فایل ZIP را Extract کن.
2) در GitHub یک Repository جدید بساز.
3) تمام محتویات داخل پوشه HotbarToChest را در Repository آپلود کن (خود پوشه را نه؛ فایل‌های داخلش را).
4) به تب Actions برو.
5) Workflow با نام Build HotbarToChest را باز کن.
6) اگر خودکار اجرا نشده، Run workflow را بزن.
7) بعد از سبز شدن Build، پایین صفحه Artifact با نام HotbarToChest-1.21.11 را دانلود کن.
8) ZIP Artifact را Extract کن. فایل hotbar-to-chest-1.0.0.jar مود نهایی است.
9) JAR را داخل پوشه mods ماینکرفت قرار بده و Fabric API را هم داخل mods داشته باش.
