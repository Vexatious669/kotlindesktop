<#
.SYNOPSIS
    Replaces the icon of a Windows PE (.exe) file with the images from an .ico file.

.DESCRIPTION
    The portable build is a self-extracting 7-Zip stub, and the SFX config format has no way to
    set an icon, so the stub's RT_GROUP_ICON / RT_ICON resources are rewritten after packaging.
    The 7-Zip GUI stub keeps its icon under resource IDs 1..n with the en-US language, so writing
    our images under those same IDs replaces them; the group entry then points at the new blobs.

.PARAMETER Path
    Path to the executable to patch.

.PARAMETER Icon
    Path to the .ico file holding the replacement images.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [Parameter(Mandatory = $true)]
    [string]$Icon
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;

public static class ExeIconPatcher
{
    private const int RtIcon = 3;
    private const int RtGroupIcon = 14;
    private const ushort EnglishUs = 0x0409;

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr BeginUpdateResource(string fileName, bool deleteExistingResources);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool UpdateResource(IntPtr update, IntPtr type, IntPtr name, ushort language, byte[] data, uint size);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool EndUpdateResource(IntPtr update, bool discard);

    public static void SetIcon(string exePath, string iconPath)
    {
        byte[] ico = File.ReadAllBytes(iconPath);
        if (ico.Length < 6 || BitConverter.ToUInt16(ico, 0) != 0 || BitConverter.ToUInt16(ico, 2) != 1)
            throw new InvalidDataException("Not an ICO file: " + iconPath);

        int count = BitConverter.ToUInt16(ico, 4);
        var images = new List<Tuple<int, byte[]>>();
        var groupEntries = new List<byte[]>();
        for (int i = 0; i < count; i++)
        {
            int entry = 6 + i * 16;
            int size = BitConverter.ToInt32(ico, entry + 8);
            int offset = BitConverter.ToInt32(ico, entry + 12);
            var data = new byte[size];
            Array.Copy(ico, offset, data, 0, size);
            images.Add(Tuple.Create(i + 1, data));

            var groupEntry = new byte[14];
            groupEntry[0] = ico[entry];     // width
            groupEntry[1] = ico[entry + 1]; // height
            groupEntry[2] = ico[entry + 2]; // color count
            groupEntry[3] = ico[entry + 3]; // reserved
            Array.Copy(ico, entry + 4, groupEntry, 4, 2);                         // planes
            Array.Copy(ico, entry + 6, groupEntry, 6, 2);                         // bit count
            Array.Copy(BitConverter.GetBytes((uint)data.Length), 0, groupEntry, 8, 4);
            Array.Copy(BitConverter.GetBytes((ushort)(i + 1)), 0, groupEntry, 12, 2);
            groupEntries.Add(groupEntry);
        }

        var group = new List<byte>();
        group.AddRange(BitConverter.GetBytes((ushort)0)); // reserved
        group.AddRange(BitConverter.GetBytes((ushort)1)); // type: icon
        group.AddRange(BitConverter.GetBytes((ushort)count));
        foreach (var entry in groupEntries)
            group.AddRange(entry);

        IntPtr update = BeginUpdateResource(exePath, false);
        if (update == IntPtr.Zero)
            throw new Win32Exception(Marshal.GetLastWin32Error(), "BeginUpdateResource failed");

        try
        {
            foreach (var image in images)
            {
                if (!UpdateResource(update, new IntPtr(RtIcon), new IntPtr(image.Item1), EnglishUs, image.Item2, (uint)image.Item2.Length))
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "UpdateResource (RT_ICON) failed");
            }
            byte[] groupBytes = group.ToArray();
            if (!UpdateResource(update, new IntPtr(RtGroupIcon), new IntPtr(1), EnglishUs, groupBytes, (uint)groupBytes.Length))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "UpdateResource (RT_GROUP_ICON) failed");
        }
        finally
        {
            if (!EndUpdateResource(update, false))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "EndUpdateResource failed");
        }
    }
}
'@

[ExeIconPatcher]::SetIcon($Path, $Icon)
Write-Host "Applied icon '$Icon' to '$Path'"
