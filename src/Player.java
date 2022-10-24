import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.UnsupportedTagException;
import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Song;

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
    private boolean shuffle = false;
    private boolean repeatSongs = false;


    //</editor-fold>

    //<editor-fold desc="Time integer variables">
    private int curTime;
    private int totalTime;
    private int newTime;
    //</editor-fold>

    //<editor-fold desc="Other variables">
    private int count = 0;
    private int currentFrame = 0;
    private int songListSize = 0;
    private int curIndex; //index of current song in the current reproduction list
    private String curID;  //Song's ID
    //</editor-fold>

    /**Lock used to apply mutual exclusion
     * when it is needed to access critical
     * regionsin the code**/
    private final Lock lockStuff = new ReentrantLock();

    //<editor-fold desc="Dynamic arrays used for easly add and remove songs from the player: ">
    private ArrayList<Song> songList = new ArrayList<>();
    private ArrayList<String[]> songDataList = new ArrayList<>();
    private ArrayList<Song> shuffleSongList = new ArrayList<>();
    private ArrayList<String[]> shuffleSongDataList = new ArrayList<>();
    //</editor-fold>

    /**Matrix that contains songs' data and will
     * be used as a paramenter when calling
     * PlayerWindow method in the constructor**/
    String [][] playlist = {};
    private Song songPlayingNow;

    //<editor-fold desc="ActionListener">
    private final ActionListener buttonListenerPlayNow = e -> {

        curID = window.getSelectedSong();
        playNow(curID);
    };
    private final ActionListener buttonListenerRemove = e -> removeSongs();
    private final ActionListener buttonListenerAddSong = e -> addSongs();
    private final ActionListener buttonListenerPlayPause = e -> playPause();
    private final ActionListener buttonListenerStop = e -> stop();
    private final ActionListener buttonListenerNext = e -> next();
    private final ActionListener buttonListenerPrevious = e -> previous();
    private final ActionListener buttonListenerShuffle = e -> shuffle();
    private final ActionListener buttonListenerLoop = e -> loop();

    //</editor-fold>

    //<editor-fold desc="Mouse">
    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) { //When releasing the Scrubber
            releasedScrubber();
        }

        @Override
        public void mousePressed(MouseEvent e) { //When pressing the Scrubber
            pressedScrubber();
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
        EventQueue.invokeLater(() -> window.setEnabledShuffleButton(true));
        EventQueue.invokeLater(() -> window.setEnabledLoopButton(true));
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

    public void addSongs() {
        Song addedSong;
        try {
            lockStuff.lock();
            addedSong = window.openFileChooser();
            String [] songData = addedSong.getDisplayInfo();
            songDataList.add(songData);
            songList.add(addedSong);
            songListSize++;
            renewQueue();

            //add song and its data also to the shuffle arrayLists
            shuffleSongList.add(addedSong);
            shuffleSongDataList.add(songData);
            //System.out.println(addedSong.getUuid()); debug print to see recently added song's ID
        }
        catch (IOException | BitstreamException | UnsupportedTagException | InvalidDataException exception){

        }
        finally {
            lockStuff.unlock();
        }
    }

    public void removeSongs() {
        try {
            String removeID;
            Song deletedSong;
            int removeIdx;
            lockStuff.lock();
            removeID = window.getSelectedSong();
            deletedSong = songList.stream().filter(song -> removeID.equals(song.getUuid())).findFirst().orElse(null); //get the Song object by its ID
            removeIdx = songList.indexOf(deletedSong);
            songList.remove(removeIdx);
            songDataList.remove(removeIdx);
            if(removeID.equals(curID)) { //if current playing song is removed, it is removed
                stop();
            }


            //remove song and its data in the shuffle ArrayLists
            for (int i = 0; i < songListSize; i++) {
                if (deletedSong == shuffleSongList.get(i)) {
                    shuffleSongList.remove(i);
                    shuffleSongDataList.remove(i);
                    break;
                }
            }

            songListSize--;
            renewQueue();
        }
        finally {
            lockStuff.unlock();
        }
    }

    public void playNow(String songID) {
        // System.out.println(songID); debug print to see ID of song that has just started playing
        count = 0;
        currentFrame = 0;

        if(playingAtTheMoment) { //if a Song is playing when the PlayNow button is pressed, stop playing it immediately
            endCurrent = true;
        }

        try {
            lockStuff.lock();
            playingAtTheMoment = true;
            songPlayingNow = songList.stream().filter(song -> songID.equals(song.getUuid())).findFirst().orElse(null);
            curIndex = songList.indexOf(songPlayingNow);

            //refresh display
            window.setPlayingSongInfo(songPlayingNow.getTitle(), songPlayingNow.getAlbum(), songPlayingNow.getArtist());
            int pausedIdx = booleanToInt(paused);
            window.setEnabledPlayPauseButton(playingAtTheMoment);
            window.setPlayPauseButtonIcon(pausedIdx);
            window.setEnabledStopButton(playingAtTheMoment);
            window.setEnabledScrubber(playingAtTheMoment);
            window.setEnabledNextButton(playingAtTheMoment);
            window.setEnabledPreviousButton(playingAtTheMoment);


            paused = false;

            try {
                device = FactoryRegistry.systemRegistry().createAudioDevice();
                device.open(decoder = new Decoder());
                bitstream = new Bitstream(songPlayingNow.getBufferedInputStream());
                playing();
            }
            catch (JavaLayerException | FileNotFoundException e) {

            }
        }
        finally {
            lockStuff.unlock();
        }
    }

    public void playPause() {
        try {
            lockStuff.lock();
            paused = !paused; //change state
            playingAtTheMoment = !paused;
            int pausedIdx = booleanToInt(paused);
            window.setPlayPauseButtonIcon(pausedIdx);
        }
        finally {
            lockStuff.unlock();
        }
    }

    public void playing() {
        playingAtTheMoment = true;
        Thread playingSong = new Thread(()-> { //the Song's reproduction routine is a process that will be running parallel to the user's other actions
            while(true) {
                //System.out.println("thread"); debug print to check if music is still on but paused
                while (!paused) {
                    //System.out.println("play"); debug print to check if music is playing
                    try {
                        if (stop || endCurrent) {  //stop the current from song playing after pressing the PlayNow button again
                            playingAtTheMoment = false;
                            break;
                        }
                        //timestuff:
                        curTime = (int) (count * songPlayingNow.getMsPerFrame());
                        totalTime = (int) songPlayingNow.getMsLength();
                        window.setTime(curTime, totalTime);

                        if (window.getScrubberValue() < songPlayingNow.getMsLength()) { //while song's not ended yet
                        playingAtTheMoment = playNextFrame();
                        }
                        else {
                            if (curIndex < songListSize-1){
                                next();
                            }
                            else{
                                if (repeatSongs) {
                                    curIndex = 0;
                                    String loopSongID = songList.get(0).getUuid();
                                    curID = loopSongID;
                                    playNow(loopSongID);
                                }
                                else {
                                    stop();
                                }
                            }
                        }
                        count++;
                    }
                    catch (JavaLayerException e) {

                    }
                }

                if(stop || endCurrent) {
                    endCurrent = false;
                    stop = false;
                    paused = false;
                    break;
                }
            }
        });
        playingSong.start();
    }

    public void renewQueue() { //refresh the queue every time a Song is added or removed
        try {
            lockStuff.lock();
            String[][] data_matrix = new String[songListSize][7];
            this.playlist = this.songDataList.toArray(data_matrix);
            window.setQueueList(this.playlist);
        }
        finally {
            lockStuff.unlock();
        }
    }

    public void stop() {
        try {
            lockStuff.lock();
            count = 0;
            playingAtTheMoment = false;
            stop = true;
            window.resetMiniPlayer();
        }
        finally {
            lockStuff.unlock();
        }
    }

    public void next() {
        try{
            lockStuff.lock();

            if (curIndex != songListSize - 1) {
                curIndex++; //go to the next index of the queue if the current one is smaller than the last index
                String nextSongID = songList.get(curIndex).getUuid();
                curID = nextSongID;
                playNow(nextSongID);
            }
            else if (curIndex == songListSize - 1 && repeatSongs) {
                curIndex = 0;
                String loopSongID = songList.get(0).getUuid();
                curID = loopSongID;
                playNow(loopSongID);
            }
        }
        finally {
            lockStuff.unlock();
        }
    }

    public void previous() {
        try {
            lockStuff.lock();

            if(curIndex != 0) {
                curIndex--; //go to the previous index of the queue if the current one is bigger than 0
                String previousSongID = songList.get(curIndex).getUuid();
                curID = previousSongID;
                playNow(previousSongID);
            }
        }
        finally {
            lockStuff.unlock();
        }
    }

    public void pressedScrubber() {
        try {
            lockStuff.lock();
            paused = true; //pause the Song when pressing the Scrubber
        }
        finally {
            lockStuff.unlock();
        }
    }

    public void releasedScrubber(){ //restart the Song at the released time in the Scrubber
        try {
            lockStuff.lock();
            try {
                currentFrame = 0;
                device = FactoryRegistry.systemRegistry().createAudioDevice();
                device.open(decoder = new Decoder());
                bitstream = new Bitstream(songPlayingNow.getBufferedInputStream());
            }
            catch (JavaLayerException | FileNotFoundException e) {
            }

            newTime = (int) (window.getScrubberValue() / songPlayingNow.getMsPerFrame());
            count = newTime;

            window.setTime((int) (count * songPlayingNow.getMsPerFrame()), totalTime);

            try {
                skipToFrame(newTime);

            } catch (BitstreamException e){
                //System.out.println(e);
            }

            if(playingAtTheMoment){
                paused = false;
            }

        }
        finally {
            lockStuff.unlock();
        }

    }

    public void loop() {
        try {
            lockStuff.lock();
            repeatSongs = !repeatSongs;
        }
        finally {
            lockStuff.unlock();
        }
    }


    public void shuffle(){
        Song shuffleSong;
        String[] shuffleSongData;

        try {
            lockStuff.lock();

            shuffle = !shuffle;
            if (playingAtTheMoment || paused){ //when there is a song playing, this song goes to index 0
                if (shuffle) {
                    //save correct order list and its data to restore them when shuffle is deactivated
                    shuffleSongList.clear();
                    shuffleSongList.addAll(songList);

                    shuffleSongDataList.clear();
                    shuffleSongDataList.addAll(songDataList);

                    //current song and its data go to index 0 of their respective lists
                    shuffleSong = songList.get(curIndex);
                    songList.set(curIndex, songList.get(0));
                    songList.set(0, shuffleSong);

                    shuffleSongData = songDataList.get(curIndex);
                    songDataList.set(curIndex, songDataList.get(0));
                    songDataList.set(0, shuffleSongData);

                    curIndex = 0;

                    //shuffle the list
                    for (int i = 1; i < songListSize; i++) {
                        int randomIdx =  (int) (Math.random() * (songListSize - i) + i);

                        shuffleSong = songList.get(randomIdx);
                        songList.set(randomIdx, songList.get(i));
                        songList.set(i, shuffleSong);

                        shuffleSongData = songDataList.get(randomIdx);
                        songDataList.set(randomIdx, songDataList.get(i));
                        songDataList.set(i, shuffleSongData);
                    }



                    renewQueue();
                }
                else {
                    //restore the original order:
                    songList.clear();
                    songList.addAll(shuffleSongList);

                    songDataList.clear();
                    songDataList.addAll(shuffleSongDataList);

                    for (int i = 0; i < songListSize; i++) {
                        if (songPlayingNow == songList.get(i)) {
                            curIndex = i;
                            break;
                        }
                    }
                    renewQueue();
                }
            }
            else {
                if (shuffle) { //the process is the same as above, except the fact that loop will iterate over all songs
                    shuffleSongList.clear();
                    shuffleSongList.addAll(songList);

                    shuffleSongDataList.clear();
                    shuffleSongDataList.addAll(songDataList);

                    for (int i = 0; i < songListSize; i++) {
                        int randomIdx =  (int) ((Math.random() * (songListSize - i)) + i);

                        shuffleSong = songList.get(randomIdx);
                        songList.set(randomIdx, songList.get(i));
                        songList.set(i, shuffleSong);

                        shuffleSongData = songDataList.get(randomIdx);
                        songDataList.set(randomIdx, songDataList.get(i));
                        songDataList.set(i, shuffleSongData);
                    }
                    renewQueue();
                }
                else {
                    songList.clear();
                    songList.addAll(shuffleSongList);

                    songDataList.clear();
                    songDataList.addAll(shuffleSongDataList);

                    for (int i = 0; i < songListSize; i++) {
                        if (songPlayingNow == songList.get(i)) {
                            curIndex = i;
                            break;
                        }
                    }
                    renewQueue();
                }
            }
        }
        finally {
            lockStuff.unlock();
        }
    }

    /**convert Boolean value to Int**/
    public int booleanToInt(boolean state){ 
        return state ? 0 : 1;
    }
}
