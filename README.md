# My backup scripts

These are my current scripts for backing up three laptops around the house to a desktop PC. It is also a small experiment in using [babashka](https://github.com/babashka/babashka), a scripting flavour of the Clojure language.

I had never used babaskha, so this is very unlikely to be a good example for anyone else. It's embarrassingly light on tests, for example. I just put one or two in to prove that tests are possible, but that's it. For much better information and examples, see babashka's [online book](https://book.babashka.org/#introduction).

## Main goals and outcomes

The main reason I was revisiting my home backup scripts it that we've transitioned over the years from a household of two or three standard PCs, to one with three laptops.

I used to mirror my family's home directories to an external drive. I used Time Machine on the Mac and [cron](http://manpages.ubuntu.com/manpages/focal/en/man3/cron.3tcl.html) and [rsync](http://manpages.ubuntu.com/manpages/focal/man1/rsync.1.html) on Linux. That mostly just worked.

Laptops are a new challenge, though. You can't just go around with an external drive hanging off them. They're often sleeping, with the lid down, on a sofa somewhere and (unless it's the work laptop) used intermittently.

My solution, at the moment, is to back up centrally (using the [server-side script](src/eamonnsullivan/backup.clj) that is the main focus of this repo), but have the laptops initiate the process by running small shell scripts over ssh. I'm using [anacron](http://manpages.ubuntu.com/manpages/focal/man8/anacron.8.html) on Ubuntu and launchctl/launchd on the Mac to configure the laptops to back themselves up once a day.

### Why do I still need backups?

That's a question worth considering. Why not just use Dropbox, Google Drive or whatever Apple's pushing these days? I do actually already use these things, plus Github for all of my source code.

But these aren't back up systems. If I accidentally delete an important file, for example, it's very shortly gone from these systems as well.

Also, my wife (who doesn't use Dropbox or anything like it) keeps her entire digital life (word processing documents, presentation, spreadsheets, etc.) on a Thinkpad running Ubuntu. She counts on me to keep those safe. (Well, actually, she just assumes they are safe... I'm the IT Guy in the house and it's my job to worry about such things.)

Mostly, though, I like having control over my own data and I want to understand how it is being stored and moved around. I will probably extend this system to the cloud in the future -- periodically sending monthly back ups offsite -- but I will likely use S3 on my own AWS account for that, keeping it under my understanding and control.

## Prerequisites

You'll need a PC that's either always on or reliably turned on when it's needed. It will need plenty of disk storage. A Raspberry Pi with an attached USB disk would be ideal, but at the moment, I'm using our remaining desktop PC running Ubuntu. It's getting on 10 years old, but it suffices, and also comes in handy to share the printer and use the scanner.

I use a 1TB SSD USB disk, mounted permanently at /media/backup. Here's [one random guide](https://www.techrepublic.com/article/how-to-properly-automount-a-drive-in-ubuntu-linux/) on how to mount an external disk automatically on boot.

You will also need to set up password-less SSH access, both from the back up PC to the devices, and from the devices to the back up PC. This will allow the back up PC to run rsync to the devices, and also allow to the devices to kick off the process by running a small shell script via SSH. There are a lot of guides on the Internet for this, such as [this one](https://linuxize.com/post/how-to-setup-passwordless-ssh-login/).

I don't use Windows at the moment and don't really mount network drives, either. That might be a good alternative to consider.

## Back up approach

I used to just mirror everyone's home directory on another device. Just periodically run rsync and sync all of the files to the external hard drive. This works, but it has a couple of disadvantages. One is that if you delete a file on Tuesday and discover on Thursday that you really actually needed that file, it's probably now gone from the back up, as well.

So, instead I took a cue from Apple's Time Machine and take advantage of hard links, a relatively underused facility in most Unix based file systems. Basically, it just give the a file another name, somewhere else. If you delete one of the names, the same file still lives under the other name. And hard links don't take up any extra space (or hardly any).

I also mitigate against creeping bad sectors (which happen even on SSDs) by periodically starting over again with a fresh, full back up.

The basic algorithm:

 1. Run rsync to sync the files from one place to another.
 1. Make a hard link of all of the files to another location on the file system, under a directory with the date in the name.
 1. Check the remaining free space on the back up device and compare it with the last full back I just did. If free space is less than twice the size of the last backup, I start removing the oldest back up directories (the ones created in the last step) until I have that much remaining.

## License

Copyright © 2021 Eamonn Sullivan

Distributed under the Eclipse Public License version 1.0.
