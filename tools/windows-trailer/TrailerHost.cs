using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal static class TrailerHost
{
    [STAThread]
    private static int Main(string[] args)
    {
        int parentId;
        long ownerHandle;
        if (args.Length < 2 || args.Length > 3 || !int.TryParse(args[0], out parentId)
            || !long.TryParse(args[1], out ownerHandle) || (args.Length == 3 && args[2] != "--probe")) return 2;
        try
        {
            var input = new char[16385];
            int count = Console.In.ReadBlock(input, 0, input.Length);
            if (count == 0 || count == input.Length) return 2;
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            using (var parent = Process.GetProcessById(parentId))
            using (var form = new TrailerForm(new string(input, 0, count), parent, args.Length == 3))
            {
                var owner = new WindowOwner(new IntPtr(ownerHandle));
                if (owner.Handle != IntPtr.Zero && IsWindow(owner.Handle))
                {
                    form.ShowInTaskbar = false;
                    form.StartPosition = FormStartPosition.CenterParent;
                    form.ShowDialog(owner);
                }
                else form.ShowDialog();
                return form.ExitCode;
            }
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error.GetType().Name);
            return 3;
        }
    }

    [DllImport("user32.dll")]
    private static extern bool IsWindow(IntPtr handle);

    private sealed class WindowOwner : IWin32Window
    {
        public WindowOwner(IntPtr handle) { Handle = handle; }
        public IntPtr Handle { get; private set; }
    }
}

internal sealed class TrailerForm : Form
{
    private const string Origin = "https://app.tvflick";
    private const string Page = Origin + "/trailer/player.html";
    private readonly byte[] document;
    private readonly Process parent;
    private readonly bool probe;
    private readonly WebView2 player;
    private readonly Timer timer = new Timer { Interval = 350 };
    private readonly Stopwatch startup = Stopwatch.StartNew();
    private bool ready;
    internal int ExitCode { get; private set; }

    internal TrailerForm(string html, Process parent, bool probe)
    {
        this.document = Encoding.UTF8.GetBytes(html);
        this.parent = parent;
        this.probe = probe;
        Text = "TV Flick · Trailer";
        BackColor = Color.Black;
        ClientSize = new Size(800, 450);
        MinimumSize = new Size(330, 260);
        StartPosition = FormStartPosition.CenterScreen;
        if (probe) { ShowInTaskbar = false; Opacity = 0; }
        player = new WebView2 { Dock = DockStyle.Fill, DefaultBackgroundColor = Color.Black };
        Controls.Add(player);
        Shown += async (sender, e) =>
        {
            try { await InitializePlayer(); }
            catch (Exception error) { Fail(error.GetType().Name); }
        };
        timer.Tick += (sender, e) =>
        {
            if (parent.HasExited) Close();
            else if (!ready && startup.ElapsedMilliseconds >= 25000) Fail("timeout");
        };
        timer.Start();
    }

    private async System.Threading.Tasks.Task InitializePlayer()
    {
        var profile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "TV Flick", "trailer-webview2");
        var env = await CoreWebView2Environment.CreateAsync(null, profile);
        if (IsDisposed) return;
        await player.EnsureCoreWebView2Async(env);
        if (IsDisposed) return;
        var core = player.CoreWebView2;
        core.Settings.AreDevToolsEnabled = false;
        core.Settings.AreDefaultContextMenusEnabled = false;
        core.Settings.AreDefaultScriptDialogsEnabled = false;
        core.Settings.IsStatusBarEnabled = false;
        core.NavigationStarting += (sender, e) => e.Cancel = e.Uri != Page;
        core.NewWindowRequested += (sender, e) => e.Handled = true;
        core.PermissionRequested += (sender, e) => e.State = CoreWebView2PermissionState.Deny;
        core.DownloadStarting += (sender, e) => e.Cancel = true;
        core.NavigationCompleted += (sender, e) => { if (!e.IsSuccess) Fail("navigation"); };
        core.ProcessFailed += (sender, e) => Fail("renderer");
        core.AddWebResourceRequestedFilter(Page, CoreWebView2WebResourceContext.Document);
        core.AddWebResourceRequestedFilter("https://www.youtube.com/embed/*", CoreWebView2WebResourceContext.Document);
        core.WebResourceRequested += (sender, e) =>
        {
            if (e.Request.Uri == Page)
                e.Response = env.CreateWebResourceResponse(new MemoryStream(document, false), 200, "OK",
                    "Content-Type: text/html; charset=utf-8\r\nCache-Control: no-store");
            else if (e.Request.Uri.StartsWith("https://www.youtube.com/embed/", StringComparison.Ordinal))
                e.Request.Headers.SetHeader("Referer", Origin + "/");
        };
        core.WebMessageReceived += (sender, e) =>
        {
            if (e.Source != Page) return;
            var message = e.TryGetWebMessageAsString();
            if (message == "ready" || message == "playing")
            {
                ready = true;
                if (probe) { Console.WriteLine("YOUTUBE_IFRAME_READY"); Close(); }
            }
            else if (message.StartsWith("error:", StringComparison.Ordinal))
                Fail(message);
        };
        core.Navigate(Page);
    }

    private void Fail(string reason)
    {
        if (IsDisposed) return;
        ExitCode = 3;
        Console.Error.WriteLine("TRAILER_UNAVAILABLE " + reason);
        Close();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) timer.Dispose();
        base.Dispose(disposing);
    }
}
