using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Windows.Forms;

[assembly: AssemblyTitle("محاسبة شركة الثقة")]
[assembly: AssemblyProduct("محاسبة شركة الثقة")]
[assembly: AssemblyCompany("شركة الثقة")]
[assembly: AssemblyVersion("1.0.0.0")]

// يفتح برنامج محاسبة شركة الثقة في نافذة خاصة به باستخدام محرّك Edge الموجود في ويندوز.
static class Program
{
    const string Url = "https://asb-70.github.io/ASB-70/muhasaba/";

    [STAThread]
    static void Main()
    {
        string pf86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
        string pf = Environment.GetEnvironmentVariable("ProgramW6432") ?? Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        string[] browsers = {
            Path.Combine(pf86, @"Microsoft\Edge\Application\msedge.exe"),
            Path.Combine(pf, @"Microsoft\Edge\Application\msedge.exe"),
            Path.Combine(pf, @"Google\Chrome\Application\chrome.exe"),
            Path.Combine(pf86, @"Google\Chrome\Application\chrome.exe"),
            Path.Combine(local, @"Google\Chrome\Application\chrome.exe")
        };
        try
        {
            foreach (string b in browsers)
            {
                if (!File.Exists(b)) continue;
                string profile = Path.Combine(local, "MuhasabaAlthiqa");
                Directory.CreateDirectory(profile);
                ProcessStartInfo si = new ProcessStartInfo(b,
                    "--app=" + Url + " --user-data-dir=\"" + profile + "\" --no-first-run --no-default-browser-check --window-size=1240,860");
                si.UseShellExecute = false;
                Process.Start(si);
                return;
            }
            ProcessStartInfo open = new ProcessStartInfo(Url);
            open.UseShellExecute = true;
            Process.Start(open);
        }
        catch (Exception e)
        {
            MessageBox.Show("تعذّر فتح البرنامج: " + e.Message, "محاسبة شركة الثقة", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }
}
