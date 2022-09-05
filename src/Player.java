import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.UnsupportedTagException;
import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Song;

import javax.swing.*;
import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;

import java.io.FileNotFoundException;
import java.io.IOException;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import java.util.ArrayList;

public class Player {

    //<editor-fold desc="Basics">

    /**
     * The MPEG audio bitstream.
     */
    private Bitstream bitstream;
    /**
     * The MPEG audio decoder.
     */
    private Decoder decoder;
    /**
     * The AudioDevice where audio samples are written to.
     */
    private AudioDevice device;

    private PlayerWindow window;
    //</editor-fold>

    //<editor-fold desc="Boolean Variables">
    private boolean playingAtTheMoment = false;
    private boolean paused = false;
    private boolean endCurrent = false;
    private boolean stop = false;
    //</editor-fold>

    //<editor-fold desc="Time integer variables">
    private int cur_time;
    private int total_time;
    //</editor-fold>

    //<editor-fold desc="Other integer variables">
    private int index;
    private int count = 0;
    private int currentFrame = 0;
    //</editor-fold>

    private final Lock lock_stuff = new ReentrantLock();

    //<editor-fold desc="Dynamic arrays used for easly add and remove songs from the player: ">
    private ArrayList<Song> songList = new ArrayList<>();
    private ArrayList<String[]> songDataList = new ArrayList<>();
    //</editor-fold>

    /**Matrix that contains songs' data and will
     * be used as a paramenter when calling
     * PlayerWindow method in the constructor**/
    String [][] playlist = {};
    private Song songPlayingNow;

    //<editor-fold desc="ActionListener">
    private final ActionListener buttonListenerPlayNow = e ->
    {
        index = window.getIndex();
        playNow(index);
    };
    private final ActionListener buttonListenerRemove = e -> removeSong();

    private final ActionListener buttonListenerAddSong = e -> add_songs();
    private final ActionListener buttonListenerPlayPause = e -> PlayPause();
    private final ActionListener buttonListenerStop = e -> stop();
    private final ActionListener buttonListenerNext = e -> {};
    private final ActionListener buttonListenerPrevious = e -> {};
    private final ActionListener buttonListenerShuffle = e -> {};
    private final ActionListener buttonListenerLoop = e -> {};

    //</editor-fold>

    //<editor-fold desc="Mouse">
    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {
        }

        @Override
        public void mousePressed(MouseEvent e) {
        }

        @Override
        public void mouseDragged(MouseEvent e) {
        }
    };
    //</editor-fold>

    public Player()
    {
        EventQueue.invokeLater(() -> window = new PlayerWindow
                (
                        "Music Player - InfraSW",
                        playlist,
                        buttonListenerPlayNow,
                        buttonListenerRemove,
                        buttonListenerAddSong,
                        buttonListenerShuffle,
                        buttonListenerPrevious,
                        buttonListenerPlayPause,
                        buttonListenerStop,
                        buttonListenerNext,
                        buttonListenerLoop,
                        scrubberMouseInputAdapter
                )
        );
    }

    //<editor-fold desc="Essential">

    /**
     * @return False if there are no more frames to play.
     */
    private boolean playNextFrame() throws JavaLayerException {
        // TODO Is this thread safe?
        if (device != null) {
            Header h = bitstream.readFrame();
            if (h == null) return false;

            SampleBuffer output = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            device.write(output.getBuffer(), 0, output.getBufferLength());
            bitstream.closeFrame();
        }
        return true;
    }

    /**
     * @return False if there are no more frames to skip.
     */
    private boolean skipNextFrame() throws BitstreamException {
        // TODO Is this thread safe?
        Header h = bitstream.readFrame();
        if (h == null) return false;
        bitstream.closeFrame();
        currentFrame++;
        return true;
    }

    /**
     * Skips bitstream to the target frame if the new frame is higher than the current one.
     *
     * @param newFrame Frame to skip to.
     * @throws BitstreamException Generic Bitstream exception.
     */
    private void skipToFrame(int newFrame) throws BitstreamException {
        // TODO Is this thread safe?
        if (newFrame > currentFrame) {
            int framesToSkip = newFrame - currentFrame;
            boolean condition = true;
            while (framesToSkip-- > 0 && condition) condition = skipNextFrame();
        }
    }

    //</editor-fold>

    public void add_songs()
    {
        Song added_song;
        try
        {
            lock_stuff.lock();
            added_song = window.openFileChooser();
            String [] song_data = added_song.getDisplayInfo();
            songDataList.add(song_data);
            songList.add(added_song);
            renewQueue();

        }
        catch (IOException | BitstreamException | UnsupportedTagException | InvalidDataException exception){}

        finally {lock_stuff.unlock();}
    }

    public void playNow(int idx)
    {
        count = 0;
        currentFrame = 0;

        if(playingAtTheMoment) {endCurrent = true;}

        try
        {
            lock_stuff.lock();
            playingAtTheMoment = true;
            songPlayingNow = songList.get(idx);

            //refresh display
            window.setPlayingSongInfo(songPlayingNow.getTitle(), songPlayingNow.getAlbum(), songPlayingNow.getArtist());
            int pausedIdx = boolean_to_int(paused);
            window.setPlayPauseButtonIcon(pausedIdx);
            window.setEnabledScrubber(playingAtTheMoment); //**
            window.setEnabledPlayPauseButton(playingAtTheMoment);
            window.setEnabledStopButton(playingAtTheMoment);

            paused = false;

            try
            {
                device = FactoryRegistry.systemRegistry().createAudioDevice();
                device.open(decoder = new Decoder());
                bitstream = new Bitstream(songPlayingNow.getBufferedInputStream());
                playing();
            }
            catch (JavaLayerException | FileNotFoundException e) {}
        }
        finally {lock_stuff.unlock();}
    }

    public void PlayPause()
    {
        paused = !paused; //change state
        playingAtTheMoment = !paused;
        int pausedIdx = boolean_to_int(paused);
        window.setPlayPauseButtonIcon(pausedIdx);
    }

    public void playing()
    {
        playingAtTheMoment = true;
        Thread playingSong = new Thread(()->
        {
            while(true)
            {
                System.out.println("thread"); //debug print to check if music is still on but paused
                while (!paused)
                {
                    System.out.println("play"); //debug print to check if music is playing
                    try
                    {
                        if (stop || endCurrent) //stop the current from song playing after pressing the PlayNow button again
                        {
                            playingAtTheMoment = false;
                            break;
                        }
                        //timestuff:
                        cur_time = (int) (count * songPlayingNow.getMsPerFrame());
                        total_time = (int) songPlayingNow.getMsLength();
                        window.setTime(cur_time, total_time);

                        if (window.getScrubberValue() < songPlayingNow.getMsLength()) //while song's not ended yet
                        {playingAtTheMoment = playNextFrame();}
                        else
                        {stop();}
                        count++;
                    }
                    catch (JavaLayerException e) {}
                }

                if(stop || endCurrent)
                {
                    endCurrent = false;
                    stop = false;
                    paused = false;
                    break;
                }
            }
        }
        );
        playingSong.start();
    }

    public void renewQueue()
    {
        String[][] data_matrix = new String[this.songDataList.size()][7];
        this.playlist = this.songDataList.toArray(data_matrix);
        window.setQueueList(this.playlist);
    }

    public void stop()
    {
        count = 0;
        playingAtTheMoment = false;
        stop = true;
        window.resetMiniPlayer();
    }

    public void removeSong()
    {
        try
        {
            int removeIdx;
            lock_stuff.lock();
            removeIdx = window.getIndex();
            songDataList.remove(removeIdx);
            songList.remove(removeIdx);

            if(removeIdx == index) {stop();}
            else if(removeIdx < index) {index--;}
            renewQueue();
        }
        finally {lock_stuff.unlock();}
    }

    public int boolean_to_int(boolean state){return state ? 0 : 1;}
}