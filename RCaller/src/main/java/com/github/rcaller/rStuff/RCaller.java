/*
 *
 RCaller, A solution for calling R from Java
 Copyright (C) 2010-2014  Mehmet Hakan Satman

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Lesser General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU Lesser General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 * Mehmet Hakan Satman - mhsatman@yahoo.com
 * http://www.mhsatman.com
 * Google code project: https://github.com/jbytecode/rcaller
 * Please visit the blog page with rcaller label:
 * http://stdioe.blogspot.com.tr/search/label/rcaller
 */
package com.github.rcaller.rStuff;

import com.github.rcaller.EventHandler;
import com.github.rcaller.MessageSaver;
import com.github.rcaller.TempFileService;
import com.github.rcaller.exception.ExecutionException;
import com.github.rcaller.exception.ParseException;
import com.github.rcaller.exception.RExecutableNotFoundException;
import com.github.rcaller.exception.RscriptExecutableNotFoundException;
import com.github.rcaller.graphics.GraphicsTheme;
import com.github.rcaller.util.Globals;

import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Mehmet Hakan Satman mhsatman@yahoo.com http://stdioe.blogspot.com
 * http://www.mhsatman.com http://code.google.com/p/rcaller
 *
 */
public class RCaller {

    private static final Logger logger = Logger.getLogger(RCaller.class.getName());

    protected String RscriptExecutable;
    protected String RExecutable;
    protected RCode rcode;
    protected ROutputParser parser;
    protected Process process;
    protected RStreamHandler rOutput = null;
    protected RStreamHandler rError = null;
    protected MessageSaver errorMessageSaver = new MessageSaver();
    protected OutputStream rInput = null;
    protected FailurePolicy failPolicy;
    //in case of R failute, how many retries have been made
    protected int retries = 0;
    //how long to wait for R to finish
    protected long maxWaitTime;
    protected TempFileService tempFileService = null;

    public RCaller() {
        this.rcode = new RCode();
        this.parser = new ROutputParser();
        this.failPolicy = FailurePolicy.RETRY_5;
        this.maxWaitTime = Long.MAX_VALUE;
        //create but do not do not activate the handlers yet
        //set the stream they listen to to null
        rOutput = new RStreamHandler(null, "Output");
        rError = new RStreamHandler(null, "Error");
        rError.addEventHandler(errorMessageSaver);
        tempFileService = new TempFileService();
        cleanRCode();
    }

    /**
     * How long this R caller will wait for the R process to terminate before
     * forcibly killing it
     *
     * @return maxWaitTime - the max time to wait
     */
    public long getMaxWaitTime() {
        return maxWaitTime;
    }

    /**
     * How long this R caller will wait for the R process to terminate before
     * forcibly killing it (in milliseconds)
     * @param maxWaitTime - the max time to wait
     */
    public void setMaxWaitTime(long maxWaitTime) {
        this.maxWaitTime = maxWaitTime;
    }

    /**
     * Stops the threads that are emptying the output and error streams of the
     * live but idle R process. If R is still working, this may cause it to
     * hang. If R has finished execution, these threads prevent the operating
     * system from shutting it down, so that the same process is used. Invoke
     * this method when you have used R online and are finished with it.
     *
     * @return true if rOutput and rError are alive, else return false
     */
    public boolean stopStreamConsumers() {
        rOutput.stop();
        rError.stop();
        return rOutput.isAlive()
                && rError.isAlive();
    }

    public void startStreamConsumers(Process process) {
        rOutput.setStream(process.getInputStream());
        rOutput.start();
        rError.setStream(process.getErrorStream());
        rError.start();
    }

    public void setRExecutable(String RExecutable) {
        File file = new File(RExecutable);
        if (!file.exists()) {
            throw new RExecutableNotFoundException("R Executable " + RExecutable + " not found");
        }
        this.RExecutable = RExecutable;
    }

    public String getCranRepos() {
        return Globals.cranRepos;
    }

    public ROutputParser getParser() {
        return parser;
    }

    public RCode getRCode() {
        return rcode;
    }

    public void setRCode(RCode rcode) {
        this.rcode = rcode;
    }

    public void setRscriptExecutable(String RscriptExecutable) {
        File file = new File(RscriptExecutable);
        if (!file.exists()) {
            throw new RscriptExecutableNotFoundException("R executable " + RscriptExecutable + " not found");
        }
        this.RscriptExecutable = RscriptExecutable;
    }

    public void setGraphicsTheme(GraphicsTheme theme) {
        Globals.theme = theme;
    }

    public final void cleanRCode() {
        rcode.clear();
    }

    public void deleteTempFiles() {
        tempFileService.deleteRCallerTempFiles();
        this.rcode.deleteTempFiles();
    }

    /**
     * Stores the current RCode contained in this RCaller in a temporary file
     * and return a reference to that file
     *
     * @return a reference to the file
     * @throws ExecutionException if a temporary file cannot
     * be created or written to
     */
    private File createRSourceFile() throws ExecutionException {
        File f;
        BufferedWriter writer = null;

        try {
            f = tempFileService.createTempFile("rcaller", "");
        } catch (IOException e) {
            throw new ExecutionException("Can not open a temporary file for storing the R Code: " + e.toString());
        }

        try {
            writer = new BufferedWriter(new FileWriter(f));
            writer.write(this.rcode.toString());
            writer.flush();
        } catch (IOException e) {
            throw new ExecutionException("Can not write to temporary file for storing the R Code: " + e.toString());
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, e.getMessage());
            }
        }

        return (f);
    }

    /**
     * Executes the code contained in this RCaller instance in s separate
     * process. Upon completion the process is killed and none of the R
     * variables are returned
     *
     * @throws ExecutionException if R cannot be run for some
     * reason
     */
    public void runOnly() throws ExecutionException {
        this.rcode.getCode().append("q(").append("\"").append("yes").append("\"").append(")\n");
        runRCode();
    }

    private Process exec(String command) throws IOException {
        return Runtime.getRuntime().exec(command);
    }

    private void runRCode() throws ExecutionException {
        if (this.RscriptExecutable == null) {
            throw new ExecutionException("RscriptExecutable is not defined. Please set this variable "
                    + "to full path of Rscript executable binary file.");
        }

        File rSourceFile = createRSourceFile();
        errorMessageSaver.resetMessage();
        int returnCode;
        try {
            //this Process object is local to this method. Do not use the public one.
            process = exec(RscriptExecutable + " " + rSourceFile.toString());
            startStreamConsumers(process);
            returnCode = process.waitFor();
        } catch (Exception e) {
            throw new ExecutionException("Can not run " + RscriptExecutable + ". Reason: " + e.toString());
        } finally {
            stopStreamConsumers();
        }
        if (returnCode != 0) {
            throw new ExecutionException("R command failed with error. Reason: " + errorMessageSaver.getMessage());
        }
    }

    /**
     * Runs the current code in the existing R instance (or in a new one) and
     * returns the R variable "var". The R process is kept alive and can be
     * re-used by invoking this method again. When you are done with this
     * process, you must explicitly stop it.
     *
     * @see #stopStreamConsumers()
     * @param var The R variable to return
     * @throws ExecutionException if R cannot be started
     */
    public void runAndReturnResultOnline(String var) throws ExecutionException {
        this.retries = 0; //assumes only one of the run* methods of this class is executing at any given time
        boolean done = false;
        do {
            if (getRetries() > 0) {
                logger.log(Level.INFO, "Retrying online R execution");
            }

            File outputFile;

            if (this.RExecutable == null) {
                if (handleRFailure("RExecutable is not defined.Please set this" + " variable to full path of R executable binary file.")) {
                    continue;
                }
            }

            try {
                outputFile = createOutputFile();
            } catch (Exception e) {
                if (handleRFailure("Can not create a temporary file for storing the R results: " + e.getMessage())) {
                    continue;
                } else {
                    throw new RuntimeException("Output file couldn't be created!");
                }
            }

            appendStandardCodeToAppend(outputFile, var);
            if (rInput == null || rOutput == null || rError == null || process == null) {
                try {
                    String commandline = RExecutable + " --vanilla";
                    process = exec(commandline);
                    rInput = process.getOutputStream();
                    startStreamConsumers(process);

                } catch (Exception e) {
                    if (handleRFailure("Can not run " + RExecutable + ". Reason: "
                            + e.toString())) {
                        continue;
                    }
                }
            }

            try {
                rInput.write(rcode.toString().getBytes());
                rInput.flush();
            } catch (IOException e) {
                if (handleRFailure("Can not send the source code to R file due to: " + e.toString())) {
                    continue;
                }
            }

            long slept = 0;
            boolean processKilled = false;
            try {
                while (!processKilled && outputFile.length() < 1) {
                    //TODO checking file length is wrong. R can still be writing to the file when
                    //java attempts to read, resulting in an xml parse exception. We need to  put in
                    //a lock file or something like that and only read when that is gone
                    Thread.sleep(1);
                    slept++;
                    if (slept > this.maxWaitTime) {
                        process.destroy();
                        stopStreamConsumers();
                        processKilled = true;
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace(); //quite lame, sorry
            }

            parser.setXMLFile(outputFile);

            try {
                parser.parse();
            } catch (ParseException e) {
                if (handleRFailure("Can not handle R results due to : " + e.toString())) {
                    continue;
                }
            }

            done = true; //if we got to there, no exceptions occurred
        } while (!done);
    }

    public void StopRCallerOnline() {
        if (process != null) {
            try {
                process.getOutputStream().write("q(\"no\")\n".getBytes());
                process.getOutputStream().flush();
                process.getOutputStream().close();
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage());
            }
            if(Globals.isWindows()){
                process.destroy();
            }
        }
    }

    /* Returns true if it is OK to try again under the current FailurePolicy
     * @param reason The reason for the failure, e.g. could not start R, could not parse
     * results, etc...
     * @param retries How many retries have been made so far. The method will take care of incrementing this
     * @throws RCallerExecutionException if no more retries are permitted, but an exception
     * still occurs
     */
    private boolean handleRFailure(String reason) throws ExecutionException {
        int maxFailures = 0;
        if (this.failPolicy == FailurePolicy.CONTINUE) {
            maxFailures = -1;
        }
        if (this.failPolicy == FailurePolicy.RETRY_1) {
            maxFailures = 1;
        }
        if (this.failPolicy == FailurePolicy.RETRY_5) {
            maxFailures = 5;

        }
        if (this.failPolicy == FailurePolicy.RETRY_10) {
            maxFailures = 10;
        }

        if (this.failPolicy == FailurePolicy.RETRY_FOREVER) {
            maxFailures = Integer.MAX_VALUE;
        }

        if (getRetries() < maxFailures) {
            retries++;
            return true;
        } else {
            throw new ExecutionException(reason
                    + " Maximum number of retries exceeded.");
        }
    }

    /**
     * Runs the current code and returns the R variable "var". The R process is
     * terminated upon completion of this method.
     *
     * @param var the R variable to return
     * @throws ExecutionException if R could be started; if a
     * temporary file to store the results could not be created; if the
     * temporary file is corrupt. The exact cause will be added to the stack
     * trace
     */
    public void runAndReturnResult(String var) throws ExecutionException {
        File outputFile = createOutputFile();
        appendStandardCodeToAppend(outputFile, var);
        runRCode();

        parser.setXMLFile(outputFile);
        try {
            parser.parse();
        } catch (Exception e) {
            Logger.getLogger(RCaller.class.getName()).log(Level.INFO, rcode.toString());
            throw new ParseException("Can not handle R results due to : " + e.getMessage());
        }
    }

    public void redirectROutputToFile(String name, boolean appendToExisting) throws FileNotFoundException {
        OutputStream fstream = new FileOutputStream(name, appendToExisting);
        redirectROutputToStream(fstream);
    }

    public void redirectROutputToStream(final OutputStream o) {
        EventHandler eh = new EventHandler() {
            public void messageReceived(String senderName, String msg) {
                try {
                    o.write(senderName.getBytes());
                    o.write(":".getBytes());
                    o.write(msg.getBytes());
                    o.write("\n".getBytes());
                    o.flush();
                } catch (IOException ex) {
                    Logger.getLogger(RCaller.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        };
        rOutput.addEventHandler(eh);
        rError.addEventHandler(eh);
    }

    /**
     * Sets the failure policy of this RCaller
     *
     * @param f - the FailurePolicy f
     * @see RCaller.FailurePolicy
     */
    public void setFailurePolicy(FailurePolicy f) {
        this.failPolicy = f;
    }

    /**
     * How many times we have retried to run the R code
     *
     * @return the retries
     */
    public int getRetries() {
        return retries;
    }

    /* Specifies the behaviour of the program in case of an exception
     */
    public enum FailurePolicy {

        RETRY_1,//retry at most once
        RETRY_5,
        RETRY_10, //retry at most 10 times
        RETRY_FOREVER,//retry until success
        CONTINUE//ignore the error and continue
    }

    private File createOutputFile() {
        try {
            return tempFileService.createTempFile("Routput", "");
        } catch (Exception e) {
            throw new ExecutionException("Can not create a tempopary file for storing the R results: " + e.getMessage());
        }
    }

    private void appendStandardCodeToAppend(File outputFile, String var) {
        rcode.getCode().append("cat(makexml(obj=").append(var).append(", name=\"").append(var).
                append("\"), file=\"").append(outputFile.toString().replace("\\", "/")).append("\")\n");
    }
}
